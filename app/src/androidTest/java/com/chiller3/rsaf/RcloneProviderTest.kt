package com.chiller3.rsaf

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KMutableProperty0

@RunWith(AndroidJUnit4::class)
class RcloneProviderTest {
    companion object {
        private const val MIME_BINARY = RcloneProvider.MIME_TYPE_BINARY
        private const val MIME_TEXT = "text/plain"
        private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR

        private fun Cursor.iterate(): Iterator<Cursor> = iterator {
            if (moveToFirst()) {
                while (true) {
                    yield(this@iterate)

                    if (!moveToNext()) {
                        break
                    }
                }
            }
        }

        private fun <R> retryTimeout(timeoutMs: Long, intervalMs: Long = 100, block: () -> R): R {
            val start = Instant.now()

            while (true) {
                try {
                    return block()
                } catch (e: Throwable) {
                    if (Duration.between(start, Instant.now()).toMillis() >= timeoutMs) {
                        throw IllegalStateException(
                            "Condition did not become true after $timeoutMs ms", e)
                    }

                    Thread.sleep(intervalMs)
                }
            }
        }

        private fun <T, R> withValue(ref: KMutableProperty0<T>, value: T, block: () -> R): R {
            val oldValue = ref.get()

            try {
                ref.set(value)
                return block()
            } finally {
                ref.set(oldValue)
            }
        }

        private fun Uri.docBasename(): String {
            val documentId = DocumentsContract.getDocumentId(this)
            val (_, name) = RcloneProvider.splitPath(documentId)
            return name
        }

        private fun Uri.docParent(): Uri {
            val documentId = DocumentsContract.getDocumentId(this)
            val (parent, _) = RcloneProvider.splitPath(documentId)
            return DocumentsContract.buildDocumentUri(authority, parent)
        }
    }

    private lateinit var appContext: Context
    private lateinit var prefs: Preferences
    private lateinit var remote: String
    private lateinit var rootDir: File
    private lateinit var rootDoc: String
    private lateinit var rootDocUri: Uri

    private fun docFromRoot(vararg components: String): String {
        var result = rootDoc

        for (component in components) {
            result = Rcbridge.rbPathJoin(result, component)
        }

        return result
    }

    private fun docUri(doc: String): Uri =
        DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY, doc)

    private fun docUriFromRoot(vararg components: String): Uri =
        docUri(docFromRoot(*components))

    @Before
    fun createTempRemote() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = Preferences(appContext)

        remote = "testing." + RandomUtils.generatePassword(16, RandomUtils.ASCII_ALPHANUMERIC)
        rootDir = File(appContext.cacheDir, remote)
        rootDir.mkdirs()
        rootDoc = "$remote:"
        rootDocUri = docUriFromRoot()

        val iq = RcloneRpc.InteractiveConfiguration(remote)
        while (true) {
            val (_, option) = iq.question ?: break

            when (option.name) {
                "type" -> iq.submit("alias")
                "remote" -> iq.submit(rootDir.toString())
                "config_fs_advanced" -> iq.submit("false")
                else -> throw IllegalStateException("Unexpected question: ${option.name}")
            }
        }

        assert(RcloneRpc.remoteNames.contains(remote))
    }

    @After
    fun deleteTempRemote() {
        RcloneRpc.deleteRemote(remote)
        rootDir.deleteRecursively()
    }

    private fun getRootFlags(): Int {
        val uri = DocumentsContract.buildRootsUri(BuildConfig.DOCUMENTS_AUTHORITY)
        // DocumentsProviders don't accept selections
        appContext.contentResolver.query(uri, null, null, null)!!.use { cursor ->
            val indexRootId = cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)
            val indexFlags = cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_FLAGS)
            val indexDocumentId = cursor.getColumnIndexOrThrow(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID)

            for (row in cursor.iterate()) {
                if (row.getString(indexRootId) == remote) {
                    assertEquals(docFromRoot(), row.getString(indexDocumentId))

                    return row.getInt(indexFlags)
                }
            }
        }

        throw IllegalStateException("Remote $remote not found")
    }

    @Test
    fun queryRoots() {
        withValue(prefs::pretendLocal, false) {
            assertEquals(
                0,
                getRootFlags() and DocumentsContract.Root.FLAG_LOCAL_ONLY,
            )
        }

        withValue(prefs::pretendLocal, true) {
            assertEquals(
                DocumentsContract.Root.FLAG_LOCAL_ONLY,
                getRootFlags() and DocumentsContract.Root.FLAG_LOCAL_ONLY,
            )
        }
    }

    @Test
    fun queryChildDocuments() {
        File(rootDir, "file.txt").apply {
            assertTrue(createNewFile())
            assertTrue(setLastModified(1234L))
        }
        File(rootDir, "dir").apply {
            assertTrue(mkdir())
            assertTrue(setLastModified(5678L))
        }

        val uri = DocumentsContract.buildChildDocumentsUri(
            BuildConfig.DOCUMENTS_AUTHORITY, rootDoc)

        appContext.contentResolver.query(uri, null, null, null)!!.use { cursor ->
            val indexDocumentId = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val indexMimeType = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE)
            val indexDisplayName = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val indexLastModified = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            for (row in cursor.iterate()) {
                val documentId = row.getString(indexDocumentId)
                val mimeType = row.getString(indexMimeType)
                val displayName = row.getString(indexDisplayName)
                val lastModified = row.getLong(indexLastModified)

                assertEquals(docFromRoot(displayName), documentId)

                when (displayName) {
                    "file.txt" -> {
                        assertEquals(MIME_TEXT, mimeType)
                        assertEquals(1234L, lastModified)
                    }
                    "dir" -> {
                        assertEquals(MIME_DIR, mimeType)
                        assertEquals(5678L, lastModified)
                    }
                    else -> throw IllegalStateException("Unexpected child: $displayName")
                }
            }
        }
    }

    @Test
    fun queryDocument() {
        val dir = File(rootDir, "dir").apply {
            assertTrue(mkdir())
            assertTrue(setLastModified(1234L))
        }
        val doc = docFromRoot(dir.name)
        val uri = docUri(doc)

        appContext.contentResolver.query(uri, null, null, null)!!.use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())

            val indexDocumentId = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val indexMimeType = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE)
            val indexDisplayName = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val indexLastModified = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            assertEquals(doc, cursor.getString(indexDocumentId))
            assertEquals(MIME_DIR, cursor.getString(indexMimeType))
            assertEquals(dir.name, cursor.getString(indexDisplayName))
            assertEquals(1234L, cursor.getLong(indexLastModified))
        }
    }

    @Test
    fun isChildDocument() {
        // Our implementation is defined to operate on paths only and will not perform IO

        val isChild = { parent: Uri, child: Uri ->
            DocumentsContract.isChildDocument(appContext.contentResolver, parent, child)
        }

        assertTrue(isChild(docUriFromRoot(), docUriFromRoot("child")))
        assertTrue(isChild(docUriFromRoot(), docUriFromRoot("nested", "child")))
        assertTrue(isChild(docUriFromRoot("dir"), docUriFromRoot("dir", "child")))
        assertTrue(isChild(docUriFromRoot("dir"), docUriFromRoot("dir", "nested", "child")))
        assertTrue(isChild(docUriFromRoot(), docUriFromRoot()))
        assertTrue(isChild(docUriFromRoot("dir"), docUriFromRoot("dir")))

        // Some apps, like X-plore, do their own URI manipulation instead of working with URIs
        // returned by queryChildDocuments(). In particular, they might add trailing slashes.
        assertTrue(isChild(docUri("${rootDoc}dir/"), docUriFromRoot("dir", "child")))
        assertTrue(isChild(docUri("${rootDoc}dir/"), docUri("${rootDoc}dir/child/")))
        assertTrue(isChild(docUri("${rootDoc}//dir/////"), docUri("${rootDoc}dir///nested//child///")))
    }

    @Test
    fun openDocument() {
        // Even though we do a synchronous upload during VFS file close on the rclone side, Android
        // internally uses FUSE to provide a file descriptor and FuseAppLoop.cc calls onRelease()
        // when receiving the FUSE_RELEASE opcode, which is not synchronous. Thus, we need to resort
        // to manual polling to determine when changes have taken place.
        val timeout = 2000L

        val file = File(rootDir, "file.txt")
        val uri = docUriFromRoot(file.name)

        appContext.contentResolver.openFileDescriptor(uri, "w")!!.use {
            Os.write(it.fileDescriptor, "helloworld".toByteArray(), 0, 10)
            Os.fsync(it.fileDescriptor)
        }
        retryTimeout(timeout) {
            assertEquals("helloworld", file.readText())
        }

        appContext.contentResolver.openFileDescriptor(uri, "rw")!!.use {
            Os.lseek(it.fileDescriptor, 5, OsConstants.SEEK_SET)
            Os.write(it.fileDescriptor, "WORLD".toByteArray(), 0, 5)
        }
        retryTimeout(timeout) {
            assertEquals("helloWORLD", file.readText())
        }

        appContext.contentResolver.openFileDescriptor(uri, "rwt")!!.use {
            Os.write(it.fileDescriptor, "bye".toByteArray(), 0, 3)
        }
        retryTimeout(timeout) {
            assertEquals("bye", file.readText())
        }

        appContext.contentResolver.openFileDescriptor(uri, "rwa")!!.use {
            Os.write(it.fileDescriptor, "world".toByteArray(), 0, 5)
        }
        retryTimeout(timeout) {
            assertEquals("byeworld", file.readText())
        }

        appContext.contentResolver.openFileDescriptor(uri, "r")!!.use {
            val data = ByteArray(8)
            Os.read(it.fileDescriptor, data, 0, data.size)
            assertArrayEquals("byeworld".toByteArray(), data)

            // EOF
            assertEquals(0, Os.read(it.fileDescriptor, data, 0, data.size))
        }
    }

    @Test
    fun createDocumentNaming() {
        data class TestCase(
            val addExt: Boolean,
            val mime: String,
            val display: String,
            val expected: String,
        )
        val testCases = listOf(
            TestCase(false, MIME_BINARY, "file1", "file1"),
            TestCase(false, MIME_TEXT, "file2", "file2"),
            TestCase(false, MIME_TEXT, "file3.txt", "file3.txt"),
            TestCase(false, MIME_DIR, "dir1", "dir1"),
            TestCase(true, MIME_BINARY, "file4", "file4"),
            TestCase(true, MIME_TEXT, "file5", "file5.txt"),
            TestCase(true, MIME_TEXT, "file6.txt", "file6.txt"),
            TestCase(true, MIME_TEXT, "file7.mp4", "file7.mp4.txt"),
            TestCase(true, MIME_DIR, "dir2", "dir2"),
        )

        for (testCase in testCases) {
            withValue(prefs::addFileExtension, testCase.addExt) {
                val childUri = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, testCase.mime, testCase.display)
                assertEquals(testCase.expected, childUri?.docBasename())
            }
        }
    }

    @Test
    fun createDocumentAndroid() {
        withValue(prefs::posixLikeSemantics, false) {
            for ((mime, name) in arrayOf(
                Pair(MIME_TEXT, "file"),
                Pair(MIME_DIR, "dir"),
            )) {
                val uniqueUris = mutableSetOf<Uri>()

                for (counter in 0..RcloneProvider.ANDROID_SEMANTICS_ATTEMPTS) {
                    val childUri = DocumentsContract.createDocument(
                        appContext.contentResolver, rootDocUri, mime, name)

                    if (counter == RcloneProvider.ANDROID_SEMANTICS_ATTEMPTS) {
                        assertNull(childUri)
                    } else {
                        assertNotNull(childUri)
                        uniqueUris.add(childUri!!)
                    }
                }

                assertEquals(RcloneProvider.ANDROID_SEMANTICS_ATTEMPTS, uniqueUris.size)
            }
        }
    }

    @Test
    fun createDocumentPosix() {
        withValue(prefs::posixLikeSemantics, true) {
            for ((mime, name) in arrayOf(
                Pair(MIME_TEXT, "file"),
                Pair(MIME_DIR, "dir"),
            )) {
                val childUri1 = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, mime, name)
                assertNotNull(childUri1)

                val childUri2 = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, mime, name)
                assertNotNull(childUri2)

                assertEquals(childUri1, childUri2)
            }

            withValue(prefs::addFileExtension, false) {
                assertNotNull(DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_TEXT, "existing_file"))
                assertNull(DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_DIR, "existing_file"))

                assertNotNull(DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_DIR, "existing_dir"))
                assertNull(DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_TEXT, "existing_dir"))
            }
        }
    }

    @Test
    fun renameDocumentAndroid() {
        withValue(prefs::posixLikeSemantics, false) {
            withValue(prefs::addFileExtension, false) {
                val childUri1 = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_TEXT, "file1.txt")
                assertNotNull(childUri1)

                val childUri2 = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_TEXT, "file2.txt")
                assertNotNull(childUri2)

                val renamedUri = DocumentsContract.renameDocument(
                    appContext.contentResolver, childUri2!!, "file1.txt")

                assertNotEquals(childUri1, renamedUri)
                assertEquals("file1(1).txt", renamedUri?.docBasename())
            }
        }
    }

    @Test
    fun renameDocumentPosix() {
        withValue(prefs::posixLikeSemantics, true) {
            withValue(prefs::addFileExtension, false) {
                // Rename file on top of directory
                val childDirUri = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_DIR, "dir")
                assertNotNull(childDirUri)

                val childFileUri = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_TEXT, "file")
                assertNotNull(childFileUri)

                var renamedUri = DocumentsContract.renameDocument(
                    appContext.contentResolver, childFileUri!!, "dir")
                assertNull(renamedUri)

                // Rename directory on top of file
                renamedUri = DocumentsContract.renameDocument(
                    appContext.contentResolver, childDirUri!!, "file")
                assertNull(renamedUri)

                // Rename file on top of file
                val childFileUri2 = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_TEXT, "file2")
                assertNotNull(childFileUri2)

                renamedUri = DocumentsContract.renameDocument(
                    appContext.contentResolver, childFileUri2!!, "file")
                assertEquals(childFileUri, renamedUri)

                // Rename directory on top of directory
                val childDirUri2 = DocumentsContract.createDocument(
                    appContext.contentResolver, rootDocUri, MIME_DIR, "dir2")
                assertNotNull(childDirUri2)

                renamedUri = DocumentsContract.renameDocument(
                    appContext.contentResolver, childDirUri2!!, "dir")
                assertEquals(childDirUri, renamedUri)
            }
        }
    }

    private fun testDelete(deleteFn: (Uri) -> Boolean) {
        val file = File(rootDir, "file").apply {
            assertTrue(createNewFile())
        }
        val dir = File(rootDir, "dir").apply {
            assertTrue(mkdir())
            File(this, "file").apply {
                assertTrue(createNewFile())
            }
        }

        val fileUri = docUriFromRoot(file.name)
        val dirUri = docUriFromRoot(dir.name)

        assertTrue(deleteFn(fileUri))
        assertTrue(deleteFn(dirUri))

        assertArrayEquals(arrayOf(), rootDir.list())
    }

    @Test
    fun deleteDocument() {
        testDelete { DocumentsContract.deleteDocument(appContext.contentResolver, it) }
    }

    @Test
    fun removeDocument() {
        testDelete { DocumentsContract.removeDocument(appContext.contentResolver, it, rootDocUri) }

        // Try deleting a file from the wrong parent
        File(rootDir, "dir1").apply {
            assertTrue(mkdir())
            File(this, "file").apply {
                assertTrue(createNewFile())
            }
        }
        File(rootDir, "dir2").apply {
            assertTrue(mkdir())
        }

        assertThrows(IllegalArgumentException::class.java) {
            DocumentsContract.removeDocument(
                appContext.contentResolver,
                docUriFromRoot("dir1", "file"),
                docUriFromRoot("dir2"),
            )
        }
    }

    private fun copyMove(source: Uri, targetParent: Uri, copy: Boolean): Uri? =
        if (copy) {
            DocumentsContract.copyDocument(appContext.contentResolver, source, targetParent)
        } else {
            DocumentsContract.moveDocument(appContext.contentResolver, source, source.docParent(),
                targetParent)
        }

    private fun testCopyMoveAndroid(copy: Boolean) {
        withValue(prefs::posixLikeSemantics, false) {
            val file = File(rootDir, "file.txt").apply {
                writeText("helloworld")
            }
            val dir = File(rootDir, "dir").apply {
                assertTrue(mkdir())
                assertTrue(File(this, "file").createNewFile())
            }

            // Copy/move file
            var newUri = copyMove(
                docUriFromRoot("file.txt"),
                docUriFromRoot(),
                copy,
            )
            assertEquals("file(1).txt", newUri?.docBasename())
            assertEquals("helloworld", File(rootDir, "file(1).txt").readText())
            if (!copy) {
                assertFalse(file.exists())
            }

            // Copy/move directory
            newUri = copyMove(
                docUriFromRoot("dir"),
                docUriFromRoot(),
                copy,
            )
            assertEquals("dir(1)", newUri?.docBasename())
            assertTrue(File(File(rootDir, "dir(1)"), "file").exists())
            if (!copy) {
                assertFalse(dir.exists())
            }
        }
    }

    @Test
    fun copyDocumentAndroid() {
        testCopyMoveAndroid(true)
    }

    @Test
    fun moveDocumentAndroid() {
        testCopyMoveAndroid(false)
    }

    private fun testCopyMovePosixTargetMissing(copy: Boolean) {
        withValue(prefs::posixLikeSemantics, true) {
            val sourceTree = File(rootDir, "source").apply {
                assertTrue(mkdir())
                assertTrue(File(this, "file").createNewFile())
                File(this, "dir").apply {
                    assertTrue(mkdir())
                    assertTrue(File(this, "file").createNewFile())
                }
            }
            val targetTree = File(rootDir, "target").apply {
                assertTrue(mkdir())
            }

            try {
                // Copy/move file to missing target
                var newUri = copyMove(
                    docUriFromRoot("source", "file"),
                    docUriFromRoot("target"),
                    copy,
                )
                assertEquals("file", newUri?.docBasename())
                assertTrue(File(targetTree, "file").exists())
                if (!copy) {
                    assertFalse(File(sourceTree, "file").exists())
                }

                // Copy/move dir to missing target
                newUri = copyMove(
                    docUriFromRoot("source", "dir"),
                    docUriFromRoot("target"),
                    copy
                )
                assertEquals("dir", newUri?.docBasename())
                assertTrue(File(File(targetTree, "dir"), "file").exists())
                if (!copy) {
                    assertFalse(File(sourceTree, "dir").exists())
                }
            } finally {
                sourceTree.deleteRecursively()
                targetTree.deleteRecursively()
            }
        }
    }

    private fun testCopyMovePosixTargetExists(copy: Boolean) {
        withValue(prefs::posixLikeSemantics, true) {
            val sourceTree = File(rootDir, "source").apply {
                assertTrue(mkdir())
                File(this, "file").apply {
                    writeText("hello top level")
                }
                File(this, "dir").apply {
                    assertTrue(mkdir())
                    File(this, "file").apply {
                        writeText("hello nested")
                    }
                    File(this, "file2").apply {
                        writeText("hello nested 2")
                    }
                }
            }
            val targetTree = File(rootDir, "target").apply {
                assertTrue(mkdir())
                File(this, "file").apply {
                    writeText("bye top level")
                }
                File(this, "dir").apply {
                    assertTrue(mkdir())
                    File(this, "file").apply {
                        writeText("bye nested")
                    }
                }
            }

            try {
                // Copy/move file to existing target
                var newUri = copyMove(
                    docUriFromRoot("source", "file"),
                    docUriFromRoot("target"),
                    copy,
                )
                assertEquals("file", newUri?.docBasename())
                assertEquals("hello top level", File(targetTree, "file").readText())
                if (!copy) {
                    assertFalse(File(sourceTree, "file").exists())
                }

                // Copy/move dir to existing target, which should merge
                newUri = copyMove(
                    docUriFromRoot("source", "dir"),
                    docUriFromRoot("target"),
                    copy
                )
                assertEquals("dir", newUri?.docBasename())
                assertEquals("hello nested", File(File(targetTree, "dir"), "file").readText())
                assertEquals("hello nested 2", File(File(targetTree, "dir"), "file2").readText())
                if (!copy) {
                    assertFalse(File(sourceTree, "dir").exists())
                }
            } finally {
                sourceTree.deleteRecursively()
                targetTree.deleteRecursively()
            }
        }
    }

    private fun testCopyMovePosixTargetConflicts(copy: Boolean) {
        withValue(prefs::posixLikeSemantics, true) {
            val sourceTree = File(rootDir, "source").apply {
                assertTrue(mkdir())
                File(this, "a").apply {
                    assertTrue(createNewFile())
                }
                File(this, "b").apply {
                    assertTrue(mkdir())
                    assertTrue(File(this, "file").createNewFile())
                }
            }
            val targetTree = File(rootDir, "target").apply {
                assertTrue(mkdir())
                File(this, "a").apply {
                    assertTrue(mkdir())
                    assertTrue(File(this, "file").createNewFile())
                }
                File(this, "b").apply {
                    assertTrue(createNewFile())
                }
            }

            try {
                // Copy/move file to existing directory
                var newUri = copyMove(
                    docUriFromRoot("source", "a"),
                    docUriFromRoot("target"),
                    copy,
                )
                assertNull(newUri)
                assertTrue(File(targetTree, "a").isDirectory)

                // Copy/move dir to existing file
                newUri = copyMove(
                    docUriFromRoot("source", "b"),
                    docUriFromRoot("target"),
                    copy
                )
                assertNull(newUri)
                assertTrue(File(targetTree, "b").isFile)
            } finally {
                sourceTree.deleteRecursively()
                targetTree.deleteRecursively()
            }
        }
    }

    @Test
    fun copyDocumentPosix() {
        testCopyMovePosixTargetMissing(true)
        testCopyMovePosixTargetExists(true)
        testCopyMovePosixTargetConflicts(true)
    }

    @Test
    fun moveDocumentPosix() {
        testCopyMovePosixTargetMissing(false)
        testCopyMovePosixTargetExists(false)
        testCopyMovePosixTargetConflicts(false)
    }
}