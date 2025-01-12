/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.UiThread
import androidx.core.app.ServiceCompat
import com.chiller3.rsaf.Notifications
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.toException
import java.io.File

class BackgroundUploadMonitorService : Service() {
    companion object {
        private val TAG = BackgroundUploadMonitorService::class.java.simpleName

        private const val EXTRA_SCAN_REMOTES = "scan_remotes"

        private const val MAX_IDLE_COUNT = 2

        private var startedWithScanOnce = false

        fun createIntent(context: Context, scanRemotes: Boolean) =
            Intent(context, BackgroundUploadMonitorService::class.java).apply {
                putExtra(EXTRA_SCAN_REMOTES, scanRemotes)
            }

        /**
         * Start this service once with remote scanning enabled. This allows rclone to pick up where
         * it left off if RSAF crashes or is killed by the system. This is called when the user
         * opens the app and when the device is first unlocked.
         */
        fun startWithScanOnce(context: Context) {
            if (startedWithScanOnce) {
                Log.d(TAG, "Already started with scan enabled once")
            } else {
                startedWithScanOnce = true
                context.startForegroundService(createIntent(context, true))
            }
        }

        private fun getFdPosAndSize(fd: Int): Pair<Long, Long> =
            // We dup() the fd to ensure that the pos and size at least refer to the same file.
            ParcelFileDescriptor.fromFd(fd).use { pfd ->
                val pos = Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
                val size = pfd.statSize

                pos to size
            }
    }

    data class Progress(val count: Int, val bytesCurrent: Long, val bytesTotal: Long)

    private sealed interface MonitorState {
        data object Inactive : MonitorState

        data object Active : MonitorState

        data class StopAfter(val remain: Int) : MonitorState

        data object Stopped : MonitorState
    }

    private lateinit var notifications: Notifications
    private lateinit var dataDataDir: File
    private lateinit var vfsCacheDir: File
    private val monitorThread = Thread(::monitorVfsCache)
    private var monitorState: MonitorState = MonitorState.Inactive
        set(state) {
            Log.d(TAG, "New monitor state: $state")
            field = state
        }
    private var monitorProgress = Progress(0, 0L, 0L)
    private var monitorScanRemotes = false
    private val handler = Handler(Looper.getMainLooper())
    // We need to keep a reference the same runnable for cancelling a delayed execution.
    private val stopNowRunnable = Runnable(::stopNow)

    private fun normalizePath(path: File): File {
        // Can't use relativeToOrNull() because it can add `..` components.
        return if (path.startsWith(dataDataDir)) {
            val relPath = path.relativeTo(dataDataDir)
            File(dataDir, relPath.path)
        } else {
            path
        }
    }

    override fun onCreate() {
        super.onCreate()

        notifications = Notifications(this)

        dataDataDir = File("/data/data", packageName)
        vfsCacheDir = normalizePath(File(cacheDir, "rclone/vfs"))

        monitorThread.start()
    }

    override fun onDestroy() {
        super.onDestroy()

        synchronized(monitorThread) {
            monitorState = MonitorState.Stopped
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        synchronized(monitorThread) {
            require(monitorState != MonitorState.Stopped)
            monitorState = MonitorState.Active

            monitorScanRemotes = intent?.getBooleanExtra(EXTRA_SCAN_REMOTES, false) == true

            handler.removeCallbacks(stopNowRunnable)
        }

        updateForegroundNotification()

        return START_NOT_STICKY
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @UiThread
    private fun updateForegroundNotification() {
        val progress = synchronized(monitorThread) { monitorProgress }
        val notification = notifications.createBackgroundUploadsNotification(progress)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, Notifications.ID_BACKGROUND_UPLOADS, notification, type)
    }

    private fun guessVfsCacheProgress(): Progress {
        val procfsFd = File("/proc/self/fd")

        var count = 0
        var totalPos = 0L
        var totalSize = 0L

        for (file in procfsFd.listFiles() ?: emptyArray()) {
            val fd = file.name.toInt()

            val target = try {
                normalizePath(File(Os.readlink(file.toString())))
            } catch (_: ErrnoException) {
                continue
            }

            if (!target.startsWith(vfsCacheDir)) {
                continue
            }

            val (filePos, fileSize) = try {
                getFdPosAndSize(fd)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query fd $fd", e)
                continue
            }

            count += 1
            totalPos += filePos
            totalSize += fileSize
        }

        return Progress(count, totalPos, totalSize)
    }

    private fun initVfsCacheRemotes() {
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

    private fun monitorVfsCache() {
        while (true) {
            val scan = synchronized(monitorThread) {
                val scan = monitorScanRemotes
                monitorScanRemotes = false
                scan
            }
            if (scan) {
                initVfsCacheRemotes()
            }

            val progress = guessVfsCacheProgress()

            synchronized(monitorThread) {
                var shouldNotify = false
                var shouldStop = false

                when (val state = monitorState) {
                    MonitorState.Inactive -> {}
                    MonitorState.Active -> {
                        if (progress.count == 0) {
                            monitorState = MonitorState.StopAfter(MAX_IDLE_COUNT)
                        }
                        shouldNotify = true
                    }
                    is MonitorState.StopAfter -> {
                        monitorState = if (progress.count == 0) {
                            val remain = state.remain - 1
                            if (remain > 0) {
                                MonitorState.StopAfter(remain)
                            } else {
                                shouldStop = true
                                MonitorState.Inactive
                            }
                        } else {
                            shouldNotify = true
                            MonitorState.Active
                        }
                    }
                    MonitorState.Stopped -> return
                }

                if (progress == monitorProgress) {
                    shouldNotify = false
                } else {
                    monitorProgress = progress
                }

                if (shouldNotify) {
                    handler.post(::updateForegroundNotification)
                }
                if (shouldStop) {
                    handler.post(stopNowRunnable)
                }
            }

            Thread.sleep(1000)
        }
    }
}
