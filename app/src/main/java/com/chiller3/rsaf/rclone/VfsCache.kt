/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.toException
import java.io.File

object VfsCache {
    data class Progress(val count: Int, val bytesCurrent: Long, val bytesTotal: Long)

    private val TAG = VfsCache::class.java.simpleName

    private val procfsFd = File("/proc/self/fd")
    private lateinit var appDataDir: File
    private lateinit var dataDataDir: File
    private lateinit var vfsCacheDir: File

    fun init(context: Context) {
        appDataDir = context.dataDir
        dataDataDir = File("/data/data", context.packageName)
        vfsCacheDir = normalizePath(File(context.cacheDir, "rclone/vfs"))
    }

    private fun normalizePath(path: File): File {
        // Can't use relativeToOrNull() because it can add `..` components.
        return if (path.startsWith(dataDataDir)) {
            val relPath = path.relativeTo(dataDataDir)
            File(appDataDir, relPath.path)
        } else {
            path
        }
    }

    private fun getFdPosAndSize(fd: Int): Pair<Long, Long> =
        // We dup() the fd to ensure that the pos and size at least refer to the same file.
        ParcelFileDescriptor.fromFd(fd).use { pfd ->
            val pos = Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            val size = pfd.statSize

            pos to size
        }

    fun guessProgress(remote: String?, withSize: Boolean): Progress {
        var count = 0
        var totalPos = 0L
        var totalSize = 0L

        val prefix = if (remote == null) {
            vfsCacheDir
        } else {
            File(vfsCacheDir, remote)
        }

        for (file in procfsFd.listFiles() ?: emptyArray()) {
            val fd = file.name.toInt()

            val target = try {
                normalizePath(File(Os.readlink(file.toString())))
            } catch (_: ErrnoException) {
                continue
            }

            if (!target.startsWith(prefix)) {
                continue
            }

            count += 1

            if (withSize) {
                val (filePos, fileSize) = try {
                    getFdPosAndSize(fd)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query fd $fd", e)
                    continue
                }

                totalPos += filePos
                totalSize += fileSize
            }
        }

        return Progress(count, totalPos, totalSize)
    }

    fun initDirtyRemotes() {
        val neededRemotes = hashSetOf<String>()

        // Only scan remotes that have things in their cache. We don't need to do a recursive
        // scan because rclone automatically removes empty cache directories.
        //
        // NOTE: This is imperfect when using aliases. Specifically with SFTP, the cache paths are
        // normally relative to the home directory. However, if an alias specifies an absolute path,
        // then the cache files are relative to the root directory. We have no way to know which is
        // correct when resuming uploads. Since information about aliases is lost in the VFS cache
        // directory structure, we always initialize the VFS for the underlying remote. Absolute
        // file paths will be uploaded to the home directory instead.
        for (remoteDir in vfsCacheDir.listFiles() ?: emptyArray()) {
            if ((remoteDir.list()?.size ?: 0) > 0) {
                neededRemotes.add(remoteDir.name)
            }
        }

        Log.d(TAG, "Initializing VFS for remotes: $neededRemotes")

        for ((remote, config) in RcloneRpc.remotes) {
            if (!neededRemotes.remove(remote)) {
                continue
            } else if (!RcloneRpc.getCustomBoolOpt(config, RcloneRpc.CUSTOM_OPT_VFS_CACHING)) {
                // The user will have to re-enable VFS caching to upload these.
                continue
            }

            val error = RbError()
            if (!Rcbridge.rbDocVfsInit("$remote:", error)) {
                val e = error.toException("rbDocVfsInit")
                Log.w(TAG, "Failed to initialize VFS for remote: $remote", e)
            }
        }

        Log.d(TAG, "Uninitialized remotes: $neededRemotes")
    }
}
