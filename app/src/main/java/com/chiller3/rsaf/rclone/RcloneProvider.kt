/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import com.chiller3.rsaf.AppLock
import com.chiller3.rsaf.BuildConfig
import com.chiller3.rsaf.Permissions
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.binding.rcbridge.RbDirEntry
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.RbFile
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.toException
import com.chiller3.rsaf.extension.toSingleLineString
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RcloneProvider : DocumentsProvider(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = RcloneProvider::class.java.simpleName

        const val MIME_TYPE_BINARY = "application/octet-stream"

        const val ANDROID_SEMANTICS_ATTEMPTS = 32

        private val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )
        private val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        private const val ROOT_FLAGS =
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        // rclone can handle all of these. We don't try to detect readability or writability and
        // just let things fail during the actual file operations. Detecting beforehand would be a
        // TOCTOU issue anyway.
        private const val DOCUMENT_FLAGS =
            DocumentsContract.Document.FLAG_SUPPORTS_COPY or
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_SUPPORTS_MOVE or
            DocumentsContract.Document.FLAG_SUPPORTS_REMOVE or
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        private val DIRECTORY_PERMS =
            OsConstants.S_IRWXU or
            OsConstants.S_IRGRP or OsConstants.S_IXGRP or
            OsConstants.S_IROTH or OsConstants.S_IXOTH
        private val FILE_PERMS =
            OsConstants.S_IRUSR or OsConstants.S_IWUSR or
            OsConstants.S_IRGRP or
            OsConstants.S_IROTH

        private fun getRootProjection(projection: Array<String>?): Array<String> =
            if (projection.isNullOrEmpty()) {
                DEFAULT_ROOT_PROJECTION
            } else {
                projection
            }

        private fun getDocumentProjection(projection: Array<String>?): Array<String> =
            if (projection.isNullOrEmpty()) {
                DEFAULT_DOCUMENT_PROJECTION
            } else {
                projection
            }

        /**
         * Notify SAF that [queryRoots] should be performed again.
         *
         * This should be called whenever the remotes or [Preferences.pretendLocal] change.
         */
        fun notifyRootsChanged(resolver: ContentResolver) {
            val rootsUri = DocumentsContract.buildRootsUri(BuildConfig.DOCUMENTS_AUTHORITY)
            resolver.notifyChange(rootsUri, null)
        }

        /** Split a document ID into the remote and path. */
        fun splitRemote(documentId: String): Pair<String, String> {
            val error = RbError()
            val split = Rcbridge.rbRemoteSplit(documentId, error)
                ?: throw error.toException("rbRemoteSplit")

            return Pair(split.remote, split.path)
        }

        /** Split a document ID into the parent document ID and leaf name. */
        fun splitPath(documentId: String): Pair<String, String> {
            val error = RbError()
            val split = Rcbridge.rbPathSplit(documentId, error)
                ?: throw error.toException("rbPathSplit")

            return Pair(split.parentDoc, split.leafName)
        }

        /**
         * Split a document ID or filename into the base and file extension
         *
         * @param isDir if true, the name is treated as a directory name and the returned extension
         * will always be null
         * @return Base component and extension. Neither include the dot delimiter.
         */
        private fun splitExt(documentIdOrName: String,
                             isDir: Boolean = false): Pair<String, String?> {
            val (parent, name) = splitPath(documentIdOrName)
            val dot = name.lastIndexOf('.')

            val (baseName, ext) = if (dot < 0 || isDir) {
                Pair(name, null)
            } else {
                Pair(name.substring(0, dot), name.substring(dot + 1))
            }

            return Pair(Rcbridge.rbPathJoin(parent, baseName), ext)
        }

        /** Normalize a document ID so that there are no duplicate or trailing slashes. */
        private fun normalize(documentId: String): String {
            val components = arrayListOf<String>()
            var currentDoc = documentId

            while (true) {
                val (parent, name) = splitPath(currentDoc)

                if (name.isNotEmpty()) {
                    components.add(name)
                }

                if (parent.isEmpty() || parent.endsWith(':')) {
                    // Root of the remote
                    components.add(parent)
                    break
                }

                currentDoc = parent
            }

            return components.asReversed().joinToString("/")
        }

        /** Split a path for traversing [VfsNode]. */
        private fun vfsPath(documentId: String): List<String> = normalize(documentId).split('/')

        /**
         * Construct a document ID with a counter.
         *
         * The counter is added before the optional extension if provided. If the counter is zero,
         * it is omitted.
         */
        private fun pathWithCounter(docBase: String, ext: String?, counter: Int): String =
            buildString {
                append(docBase)

                if (counter != 0) {
                    append('(')
                    append(counter)
                    append(')')
                }

                if (ext != null) {
                    append('.')
                    append(ext)
                }
            }

        /** Method for determining if there's a conflicting target path. */
        private enum class ConflictDetection {
            /** The path will be stat'ed to determine if it exists. */
            STAT,
            /**
             * The operation will throw [ErrnoException] with [OsConstants.EEXIST] if the path
             * already exists.
             */
            EEXIST,
        }

        /**
         * Find a unique document that does not exist by adding a counter suffix if needed.
         *
         * @param method The method for determining if the target path already exists.
         *
         * @throws IOException if [ErrnoException] with [OsConstants.EEXIST] is thrown every time
         */
        private fun retryUnique(baseDocumentId: String, ext: String?, method: ConflictDetection,
                                block: (String) -> Unit): String {
            for (counter in 0 until ANDROID_SEMANTICS_ATTEMPTS) {
                val documentId = pathWithCounter(baseDocumentId, ext, counter)

                if (method == ConflictDetection.STAT && documentExists(documentId)) {
                    continue
                }

                try {
                    block(documentId)
                } catch (e: ErrnoException) {
                    if (method == ConflictDetection.EEXIST && e.errno == OsConstants.EEXIST) {
                        continue
                    } else {
                        throw e
                    }
                }
                return documentId
            }

            throw IOException("Failed to find unique file")
        }

        /**
         * Check if a document exists.
         *
         * This does not throw.
         */
        private fun documentExists(documentId: String): Boolean =
            Rcbridge.rbDocStat(documentId, null) != null

        /**
         * Check if a document is a directory.
         *
         * This does not throw.
         */
        private fun documentIsDir(documentId: String): Boolean {
            val stat = Rcbridge.rbDocStat(documentId, null) ?: return false
            return OsConstants.S_ISDIR(stat.mode.toInt())
        }

        /**
         * Add a cursor row corresponding to a document directory entry.
         *
         * If the MIME type cannot be determined from the filename, then it is set to
         * [MIME_TYPE_BINARY].
         */
        private fun addRowByDirEntry(row: MatrixCursor.RowBuilder, entry: RbDirEntry) {
            var flags = DOCUMENT_FLAGS
            var mimeType: String

            if (OsConstants.S_ISDIR(entry.mode.toInt())) {
                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                val ext = entry.name.substringAfterLast('.', "")
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
                if (mimeType.isEmpty()) {
                    mimeType = MIME_TYPE_BINARY
                }
            }

            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, entry.doc)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, entry.name)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            row.add(DocumentsContract.Document.COLUMN_SIZE, entry.size)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, entry.modTime)
        }
    }

    private lateinit var prefs: Preferences
    private val ioThread = HandlerThread(javaClass.simpleName).apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    // Because it is impossible to make close() blocking, we can't force the client app to wait for
    // file uploads to complete. This causes a problem with the design pattern where a file is
    // initially written to a temp file and then renamed when complete. The rename can happen while
    // the VFS file is still open, which rclone does not properly support. Instead, we'll track
    // which files are open and block certain operations, like file renaming. We also do this for
    // file copies and moves because those operations happen at the FS layer and can't see pending
    // uploads in the VFS layer.
    //
    // Note that this is not implementing a full-blown locking system to keep all VFS operations
    // consistent. It is just a dumb workaround to make some common file access patterns work. A
    // client app can absolutely still shoot itself in the foot.
    private val inUseTracker = VfsNode()
    private val thumbnailTaskPool =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    private fun waitUntilUploadsDone(documentId: String) {
        val path = vfsPath(documentId)

        synchronized(inUseTracker) {
            while (inUseTracker.contains(path)) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (inUseTracker as Object).wait()
            }
        }
    }

    private fun debugLog(msg: String) {
        if (prefs.isDebugMode) {
            Log.d(TAG, msg)
        }
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        debugLog("notifyChildrenChanged($parentDocumentId)")

        val uri = DocumentsContract.buildChildDocumentsUri(
            BuildConfig.DOCUMENTS_AUTHORITY, parentDocumentId)
        context!!.contentResolver.notifyChange(uri, null)
    }

    private fun revokeGrants(documentId: String) {
        val uri = DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY, documentId)
        context!!.revokeUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION and Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    override fun onCreate(): Boolean {
        val context = context!!

        prefs = Preferences(context)
        prefs.registerListener(this)

        // Some of the rclone backend packages' init() functions set default values based on the
        // value of config.GetCacheDir(). However, there's no way to call config.SetCacheDir() early
        // enough that it'll be set before those init() functions. Instead, we'll set the
        // XDG_CACHE_HOME environment variable to achieve the same effect. Environment variables set
        // in libc's global environ variable are never read by golang, so rcbridge has an envhack
        // package to explicitly copy env vars from C land to Go land.
        Os.setenv("XDG_CACHE_HOME", context.cacheDir.path, true)

        Rcbridge.rbInit()
        RcloneConfig.init(context)
        updateRcloneVerbosity()

        return true
    }

    private fun updateRcloneVerbosity() {
        val verbosity = if (prefs.isDebugMode) {
            if (prefs.verboseRcloneLogs) {
                2L
            } else {
                1L
            }
        } else {
            0L
        }
        Rcbridge.rbSetLogVerbosity(verbosity)
    }

    override fun shutdown() {
        prefs.unregisterListener(this)

        thumbnailTaskPool.shutdown()
        thumbnailTaskPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Preferences.PREF_PRETEND_LOCAL -> notifyRootsChanged(context!!.contentResolver)
            Preferences.PREF_DEBUG_MODE, Preferences.PREF_VERBOSE_RCLONE_LOGS ->
                updateRcloneVerbosity()
        }
    }

    private fun enforceNotBlocked(vararg documentIds: String) {
        val configs = RcloneRpc.remotes

        for (documentId in documentIds) {
            val remote = splitRemote(documentId).first.trimEnd(':')
            if (remote.isEmpty()) {
                // Local paths are not exposed remotes in SAF.
                continue
            }

            val config = configs[remote]
                ?: throw IllegalArgumentException("Remote does not exist: $remote")

            if (RcloneRpc.getCustomBoolOpt(config, RcloneRpc.CUSTOM_OPT_HARD_BLOCKED)) {
                throw SecurityException("Access to remote is hard blocked: $remote")
            }

            val softBlocked = RcloneRpc.getCustomBoolOpt(config, RcloneRpc.CUSTOM_OPT_SOFT_BLOCKED)
            if (softBlocked && AppLock.isLocked) {
                throw FileNotFoundException("Remote inaccessible while app is locked: $remote")
            }
        }
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        debugLog("queryRoots(${projection.contentToString()})")

        return MatrixCursor(getRootProjection(projection)).apply {
            var flags = ROOT_FLAGS
            if (prefs.pretendLocal) {
                flags = flags or DocumentsContract.Root.FLAG_LOCAL_ONLY
            }

            for ((remote, config) in RcloneRpc.remotes) {
                if (RcloneRpc.getCustomBoolOpt(config, RcloneRpc.CUSTOM_OPT_HARD_BLOCKED)) {
                    debugLog("Skipping blocked remote: $remote")
                    continue
                }

                val usage = if (RcloneRpc.getCustomBoolOpt(config, RcloneRpc.CUSTOM_OPT_REPORT_USAGE)) {
                    debugLog("Querying filesystem usage: $remote")
                    RcloneRpc.getUsage("$remote:")
                } else {
                    null
                }

                newRow().apply {
                    // Required
                    add(DocumentsContract.Root.COLUMN_ROOT_ID, remote)
                    add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
                    add(DocumentsContract.Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                    add(DocumentsContract.Root.COLUMN_FLAGS, flags)
                    add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "$remote:")

                    // Optional
                    add(DocumentsContract.Root.COLUMN_SUMMARY, remote)

                    usage?.total?.let {
                        debugLog("Remote reports total space: $remote: $it")
                        add(DocumentsContract.Root.COLUMN_CAPACITY_BYTES, it)
                    }
                    usage?.free?.let {
                        debugLog("Remote reports free space: $remote: $it")
                        add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, it)
                    }
                }
            }
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?,
                                     sortOrder: String?): Cursor {
        debugLog("queryChildDocuments($parentDocumentId, ${projection.contentToString()}, $sortOrder)")
        enforceNotBlocked(parentDocumentId)

        val error = RbError()

        return MatrixCursor(getDocumentProjection(projection)).apply {
            val entries = Rcbridge.rbDocListDir(parentDocumentId, error)
                ?: throw error.toException("rbDocListDir")

            for (i in 0 until entries.size()) {
                addRowByDirEntry(newRow(), entries.get(i))
            }

            val uri = DocumentsContract.buildChildDocumentsUri(
                BuildConfig.DOCUMENTS_AUTHORITY, parentDocumentId)
            setNotificationUri(context!!.contentResolver, uri)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        debugLog("queryDocument($documentId, ${projection.contentToString()})")
        enforceNotBlocked(documentId)

        val error = RbError()
        val entry = Rcbridge.rbDocStat(documentId, error)
            ?: throw error.toException("rbDocStat")

        return MatrixCursor(getDocumentProjection(projection)).apply {
            addRowByDirEntry(newRow(), entry)
        }
    }

    /**
     * Check if a document is a child of another document.
     *
     * This does not perform I/O. The result is computed from the document IDs only.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        debugLog("isChildDocument($parentDocumentId, $documentId)")
        enforceNotBlocked(parentDocumentId, documentId)

        // AOSP's FileSystemProvider [1] returns true [2] if parentDocumentId and documentId refer
        // to the same path, but this conflicts with the documented behavior of isChildDocument(),
        // which is supposed to test "if a document is descendant (child, grandchild, etc) from the
        // given parent". We'll match AOSP's behavior because some apps expect providers to behave
        // this way.
        //
        // [1] https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:frameworks/base/core/java/com/android/internal/content/FileSystemProvider.java;l=141
        // [2] https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:frameworks/base/core/java/android/os/FileUtils.java;l=917

        val normalizedParentDocumentId = normalize(parentDocumentId)
        val normalizedDocumentId = normalize(documentId)

        return normalizedDocumentId == normalizedParentDocumentId
                || normalizedDocumentId.startsWith("$normalizedParentDocumentId/")
    }

    /**
     * Open a document.
     *
     * This provides a file descriptor that supports random reads and writes. If the rclone backend
     * does not support random writes, the file may be buffered to disk before being uploaded.
     */
    override fun openDocument(documentId: String, mode: String,
                              signal: CancellationSignal?): ParcelFileDescriptor {
        debugLog("openDocument($documentId, $mode, $signal)")
        enforceNotBlocked(documentId)

        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        val pfdModeHasFlags = { flags: Int ->
            (pfdMode and flags) == flags
        }

        var (fcntlMode, isWrite) = if (pfdModeHasFlags(ParcelFileDescriptor.MODE_READ_WRITE)) {
            OsConstants.O_RDWR to true
        } else if (pfdModeHasFlags(ParcelFileDescriptor.MODE_WRITE_ONLY)) {
            OsConstants.O_WRONLY to true
        } else if (pfdModeHasFlags(ParcelFileDescriptor.MODE_READ_ONLY)) {
            OsConstants.O_RDONLY to false
        } else {
            throw IllegalArgumentException("Invalid mode: $mode")
        }

        if (pfdModeHasFlags(ParcelFileDescriptor.MODE_CREATE)) {
            fcntlMode = fcntlMode or OsConstants.O_CREAT
        }
        if (pfdModeHasFlags(ParcelFileDescriptor.MODE_TRUNCATE)) {
            fcntlMode = fcntlMode or OsConstants.O_TRUNC
        }
        if (pfdModeHasFlags(ParcelFileDescriptor.MODE_APPEND)) {
            fcntlMode = fcntlMode or OsConstants.O_APPEND
        }

        val error = RbError()

        val handle = Rcbridge.rbDocOpen(documentId, fcntlMode.toLong(), FILE_PERMS.toLong(), error)
            ?: throw error.toException("rbDocOpen")

        val storageManager = context!!.getSystemService(StorageManager::class.java)
        val proxyFd = ProxyFd(documentId, handle, isWrite)

        try {
            return storageManager.openProxyFileDescriptor(pfdMode, proxyFd, ioHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open proxy file descriptor", e)
            // openProxyFileDescriptor can throw an exception without invoking onRelease
            handle.close(null)
            proxyFd.markUnused()
            throw e
        }
    }

    /**
     * Open a document thumbnail.
     *
     * This is only implemented for audio, image, and video files. If generating a thumbnail is
     * supported, but the process fails, the client will see an empty file.
     */
    override fun openDocumentThumbnail(documentId: String, sizeHint: Point,
                                       signal: CancellationSignal?): AssetFileDescriptor? {
        debugLog("openDocumentThumbnail($documentId, $sizeHint, $signal)")
        enforceNotBlocked(documentId)

        val projection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val mimeType = queryDocument(documentId, projection).use { cursor ->
            if (!cursor.moveToFirst()) {
                // Should never happen.
                return null
            }

            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            cursor.getString(index)
        }

        if (!Thumbnailer.isSupported(mimeType)) {
            Log.d(TAG, "Thumbnail not supported for: $mimeType")
            return null
        }

        val mediaInput = openDocument(documentId, "r", signal)

        try {
            val pipe = ParcelFileDescriptor.createReliablePipe()

            // The task owns both file descriptors.
            val task = ThumbnailTask(documentId, mediaInput, pipe[1], mimeType, sizeHint, signal)

            thumbnailTaskPool.submit(task)

            return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create thumbnail pipe", e)
            mediaInput.close()
            return null
        }
    }

    /**
     * Create a document.
     *
     * If the target already exists, a counter suffix is added to the display name to find a unique
     * name. If a unique name cannot be found, the operation will fail.
     *
     * @param displayName The filename. If [Preferences.addFileExtension] is enabled and the
     * specified MIME type is known to Android, the corresponding extension will be appended to the
     * filename unless that exact extension is already included.
     * @throws IOException if the target already exists and adding a counter suffix was not
     * sufficient to find a unique target
     */
    override fun createDocument(parentDocumentId: String, mimeType: String,
                                displayName: String): String {
        debugLog("createDocument($parentDocumentId, $mimeType, $displayName)")
        enforceNotBlocked(parentDocumentId)

        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        // Adding a .bin extension is allowed, but AOSP's FileSystemProvider does not do it and
        // some applications rely on that being the case.
        val allowExt = !isDir && mimeType != MIME_TYPE_BINARY

        var (baseName, ext) = splitExt(displayName, isDir)
        val expectedExt = if (allowExt && prefs.addFileExtension) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        } else {
            null
        }
        if (ext != expectedExt) {
            if (ext != null) {
                baseName += ".$ext"
            }
            ext = expectedExt
        }
        val base = Rcbridge.rbPathJoin(parentDocumentId, baseName)
        val error = RbError()

        // We can skip the stat and avoid TOCTOU when creating files because it properly fails with
        // EEXIST. Unfortunately, this is not the case with mkdir.
        val method = if (isDir) {
            ConflictDetection.STAT
        } else {
            ConflictDetection.EEXIST
        }

        return retryUnique(base, ext, method) {
            if (isDir) {
                if (!Rcbridge.rbDocMkdir(it, DIRECTORY_PERMS.toLong(), error)) {
                    // This is unfortunately racy, but we need to use a different error code than
                    // EEXIST when the user tries to create a directory on top of a file
                    if (!documentIsDir(it)) {
                        error.code = OsConstants.ENOTDIR.toLong()
                    }

                    throw error.toException("rbDocMkdir")
                }
            } else {
                val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL
                val handle = Rcbridge.rbDocOpen(it, flags.toLong(), FILE_PERMS.toLong(), error)
                    ?: throw error.toException("rbDocOpen")

                if (!handle.close(error)) {
                    throw error.toException("RbFile.close")
                }
            }
        }.also {
            notifyChildrenChanged(parentDocumentId)
        }
    }

    /**
     * Rename a document.
     *
     * If the target already exists, a counter suffix is added to the display name (before the
     * extension, if any) to find a unique name. If a unique name cannot be found, the operation
     * will fail. The check is done in a way that is subject to TOCTOU issues because the underlying
     * rclone operation follows the POSIX semantics of overwriting a target that already exists.
     *
     * @param displayName The filename, including the extension (if any)
     * @throws IOException if the target already exists and adding a counter suffix was not
     * sufficient to find a unique target
     */
    override fun renameDocument(documentId: String, displayName: String): String {
        debugLog("renameDocument($documentId, $displayName)")
        enforceNotBlocked(documentId)

        waitUntilUploadsDone(documentId)

        // The displayName will include the extension because our queryDocument() exposes it
        val (parent, _) = splitPath(documentId)
        val (baseName, ext) = splitExt(displayName, documentIsDir(documentId))
        val base = Rcbridge.rbPathJoin(parent, baseName)
        val error = RbError()

        return retryUnique(base, ext, ConflictDetection.STAT) {
            if (!Rcbridge.rbDocRename(documentId, it, error)) {
                throw error.toException("rbDocRename")
            }
        }.also {
            notifyChildrenChanged(parent)
        }
    }

    /**
     * Recursively delete a document.
     *
     * This does not fail if the document has already been deleted.
     */
    override fun deleteDocument(documentId: String) {
        debugLog("deleteDocument($documentId)")
        enforceNotBlocked(documentId)

        val error = RbError()
        if (!Rcbridge.rbDocRemove(documentId, true, error)
            && error.code.toInt() != OsConstants.ENOENT) {
            throw error.toException("rbDocRemove")
        }

        revokeGrants(documentId)

        val (parent, _) = splitPath(documentId)
        notifyChildrenChanged(parent)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        debugLog("removeDocument($documentId, $parentDocumentId)")
        enforceNotBlocked(documentId, parentDocumentId)

        if (!isChildDocument(parentDocumentId, documentId)) {
            throw IllegalArgumentException("$documentId is not a child of $parentDocumentId")
        }

        deleteDocument(documentId)
    }

    /**
     * Try to copy or move a document, attempting to ensure that the target is unique.
     *
     * If the target already exists, a counter suffix is added to the display name (before the
     * extension, if any) to find a unique name. If a unique name cannot be found, the operation
     * will fail. The check is done in a way that is subject to TOCTOU issues because the underlying
     * rclone operation overwrites conflicting files and merges directories.
     *
     * @throws IOException if the target already exists and adding a counter suffix was not
     * sufficient to find a unique target
     */
    private fun copyOrMove(sourceDocumentId: String, targetParentDocumentId: String,
                           copy: Boolean): String {
        val (sourceParentDocumentId, fileName) = splitPath(sourceDocumentId)
        val (baseName, ext) = splitExt(fileName, documentIsDir(sourceDocumentId))
        val targetBaseDocumentId = Rcbridge.rbPathJoin(targetParentDocumentId, baseName)
        val error = RbError()

        return retryUnique(targetBaseDocumentId, ext, ConflictDetection.STAT) {
            if (!Rcbridge.rbDocCopyOrMove(sourceDocumentId, it, copy, error)) {
                throw error.toException("rbDocCopyOrMove")
            }
        }.also {
            notifyChildrenChanged(sourceParentDocumentId)
            notifyChildrenChanged(targetParentDocumentId)
        }
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        debugLog("copyDocument($sourceDocumentId, $targetParentDocumentId)")
        enforceNotBlocked(sourceDocumentId, targetParentDocumentId)

        waitUntilUploadsDone(sourceDocumentId)

        return copyOrMove(sourceDocumentId, targetParentDocumentId, true)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String,
                              targetParentDocumentId: String): String {
        debugLog("moveDocument($sourceDocumentId, $sourceParentDocumentId, $targetParentDocumentId)")
        enforceNotBlocked(sourceDocumentId, sourceParentDocumentId, targetParentDocumentId)

        waitUntilUploadsDone(sourceDocumentId)

        return copyOrMove(sourceDocumentId, targetParentDocumentId, false).also {
            revokeGrants(sourceDocumentId)
        }
    }

    private inner class ProxyFd(
        private val documentId: String,
        private val handle: RbFile,
        private val isWrite: Boolean
    ) : ProxyFileDescriptorCallback() {
        init {
            markUsed()

            val context = context!!

            if (Permissions.isInhibitingBatteryOpt(context)) {
                context.startForegroundService(OpenFilesService.createIncrementIntent(context))
            }
        }

        private fun debugLog(msg: String) {
            this@RcloneProvider.debugLog("ProxyFd[$documentId].$msg")
        }

        override fun onGetSize(): Long {
            debugLog("onGetSize()")

            val error = RbError()
            val size = handle.getSize(error)
            if (size < 0) {
                throw error.toException("RbFile.getSize")
            }
            return size
        }

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            // This is too verbose
            if (prefs.isDebugMode && prefs.verboseRcloneLogs) {
                debugLog("onRead($offset, $size, <data[${data.size}]>)")
            }

            val error = RbError()
            val n = handle.readAt(data, size.toLong(), offset, error)
            if (n < 0) {
                throw error.toException("RbFile.readAt")
            }
            return n.toInt()
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            // This is too verbose
            if (prefs.isDebugMode && prefs.verboseRcloneLogs) {
                debugLog("onWrite($offset, $size, <data[${data.size}]>)")
            }

            val error = RbError()
            val n = handle.writeAt(data, size.toLong(), offset, error)
            if (n < 0) {
                throw error.toException("RbFile.writeAt")
            }
            return n.toInt()
        }

        override fun onFsync() {
            debugLog("onFsync()")

            val error = RbError()
            if (!handle.flush(error)) {
                throw error.toException("RbFile.flush")
            }
        }

        override fun onRelease() {
            debugLog("onRelease()")

            val context = context!!

            if (Permissions.isInhibitingBatteryOpt(context)) {
                context.startForegroundService(OpenFilesService.createDecrementIntent(context))

                if (isWrite) {
                    context.startForegroundService(
                        BackgroundUploadMonitorService.createIntent(context, false),
                    )
                }
            }

            val error = RbError()
            if (!handle.close(error)) {
                val exception = error.toException("RbFile.close")

                // This method is not supposed to throw.
                Log.w(TAG, "Error when closing file", exception)
            }

            markUnused()

            debugLog("onRelease() complete")
        }

        private fun markUsed() {
            if (isWrite) {
                synchronized(inUseTracker) {
                    debugLog("markUsed()")

                    inUseTracker.add(vfsPath(documentId))
                }
            }
        }

        fun markUnused() {
            if (isWrite) {
                synchronized(inUseTracker) {
                    debugLog("markUnused()")

                    inUseTracker.remove(vfsPath(documentId))

                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (inUseTracker as Object).notifyAll()
                }
            }
        }
    }

    private class VfsNode {
        // Number of times this node was added as a leaf.
        private var count: Int = 0
        private val children = HashMap<String, VfsNode>()

        fun contains(components: List<String>): Boolean {
            var node = this

            for (component in components) {
                node = node.children[component] ?: return false
            }

            return true
        }

        fun add(components: List<String>) {
            var node = this

            for (component in components) {
                node = node.children[component] ?: run {
                    val child = VfsNode()
                    node.children[component] = child
                    child
                }
            }

            node.count += 1
        }

        fun remove(components: List<String>) {
            if (components.isEmpty()) {
                return
            }

            val nodes = mutableListOf<Pair<String?, VfsNode>>(null to this)

            for (component in components) {
                val (_, parent) = nodes.last()
                val child = parent.children[component] ?: return
                nodes.add(component to child)
            }

            val lastNode = nodes.last().second
            if (lastNode.count > 0) {
                lastNode.count -= 1
                if (lastNode.count > 0) {
                    return
                }
            }

            for ((childItem, parentItem) in nodes.asReversed().windowed(2)) {
                val (childName, childNode) = childItem
                val (_, parentNode) = parentItem

                if (childNode.children.isEmpty() && childNode.count == 0) {
                    parentNode.children.remove(childName)
                } else {
                    break
                }
            }
        }

        private fun toString(builder: StringBuilder, depth: Int) {
            for ((name, node) in children.entries.sortedBy { it.key }) {
                builder.append("  ".repeat(depth))
                builder.append(name)
                builder.append(" (count: ")
                builder.append(node.count)
                builder.append(")\n")

                node.toString(builder, depth + 1)
            }
        }

        override fun toString(): String = buildString {
            toString(this, 0)
        }
    }

    private inner class ThumbnailTask(
        private val documentId: String,
        private val mediaInput: ParcelFileDescriptor,
        private val thumbnailOutput: ParcelFileDescriptor,
        private val mimeType: String,
        private val sizeHint: Point,
        private val signal: CancellationSignal?,
    ) : Runnable {
        override fun run() {
            debugLog("ThumbnailTask[$documentId].run()")

            // Since we're accessing the data through ProxyFd, we don't need to try and keep the
            // process alive during this process.

            mediaInput.use { input ->
                // We'll try to close with an error message if possible, in case the client is able
                // to make use of that. There's no other way of indicating an error or that a
                // thumbnail is unavailable. A client that doesn't check for errors will just see an
                // empty file.
                ParcelFileDescriptor.AutoCloseOutputStream(thumbnailOutput).use { output ->
                    try {
                        val bitmap = Thumbnailer.createThumbnail(
                            input.fileDescriptor,
                            mimeType,
                            Size(sizeHint.x, sizeHint.y),
                            signal,
                        )

                        try {
                            signal?.throwIfCanceled()

                            bitmap.compress(Bitmap.CompressFormat.PNG, 0, output)
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to generate thumbnail", e)
                        thumbnailOutput.closeWithError(e.toSingleLineString())
                    }
                }
            }
        }
    }
}
