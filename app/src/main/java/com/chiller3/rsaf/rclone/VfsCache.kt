/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.Context
import android.util.Log
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.RbVfsOpt
import com.chiller3.rsaf.binding.rcbridge.RbVfsOptList
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.toException
import java.io.File

object VfsCache {
    data class SyncProgress(val uploading: Int)

    data class AsyncProgress(val uploading: Int, val pending: Int) {
        val total = uploading + pending
    }

    private val TAG = VfsCache::class.java.simpleName

    private lateinit var appDataDir: File
    private lateinit var dataDataDir: File
    private lateinit var vfsCacheDir: File

    private val syncUploads = mutableMapOf<String, Int>()

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

    private fun normalizeRemote(s: String): String {
        return RcloneProvider.splitRemote(s).first.trimEnd(':')
    }

    fun syncUploadIncrement(remote: String) {
        synchronized(syncUploads) {
            syncUploads[remote] = syncUploads.getOrDefault(remote, 0) + 1
        }
    }

    fun syncUploadDecrement(remote: String) {
        synchronized(syncUploads) {
            val count = syncUploads[remote]!! - 1
            if (count == 0) {
                syncUploads.remove(remote)
            } else {
                syncUploads[remote] = count
            }
        }
    }

    fun syncUploadProgress(remote: String?): SyncProgress {
        synchronized(syncUploads) {
            var uploading = 0

            if (remote == null) {
                for (value in syncUploads.values) {
                    uploading += value
                }
            } else {
                uploading += syncUploads[remote] ?: 0
            }

            return SyncProgress(uploading)
        }
    }

    fun asyncUploadProgress(remote: String?): AsyncProgress {
        var uploading = 0
        var pending = 0

        val vfses = RcloneRpc.vfses.asSequence().filter {
            remote == null || remote == normalizeRemote(it)
        }
        for (vfs in vfses) {
            val vfsQueueStats = try {
                RcloneRpc.vfsQueueStats(vfs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query VFS queue stats for $vfs", e)
                continue
            }

            uploading += vfsQueueStats.inProgress
            pending += vfsQueueStats.pending
        }

        return AsyncProgress(uploading, pending)
    }

    fun hasOngoingUploads(remote: String?): Boolean =
        syncUploadProgress(remote).uploading > 0 || asyncUploadProgress(remote).total > 0

    /** Get complete VFS options, falling back to default values when not overridden. */
    fun getVfsOptions(overrides: Map<String, String>): Map<String, String> {
        val overridesList = RbVfsOptList()
        for ((key, value) in overrides) {
            val opt = RbVfsOpt()
            opt.key = key
            opt.value = value

            overridesList.add(opt)
        }

        val error = RbError()
        val vfsOptionsList = Rcbridge.rbVfsGetOpts(overridesList, error)
            ?: throw error.toException("rbVfsGetOpts")

        val vfsOptions = mutableMapOf<String, String>()

        for (i in 0 until vfsOptionsList.size()) {
            val vfsOption = vfsOptionsList.get(i)

            vfsOptions[vfsOption.key] = vfsOption.value
        }

        return vfsOptions
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

        for ((remote, config) in RcloneRpc.remoteConfigs) {
            if (!neededRemotes.remove(remote)) {
                continue
            }

            val vfsOptions = try {
                getVfsOptions(config.vfsOptions)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid VFS option overrides for remote: $remote", e)
                continue
            }
            if (vfsOptions["vfs_cache_mode"] == "off") {
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
