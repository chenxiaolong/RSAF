/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
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
import java.io.File

class BackgroundUploadMonitorService : Service() {
    companion object {
        private val TAG = BackgroundUploadMonitorService::class.java.simpleName

        private val ACTION_INCREMENT =
            "${BackgroundUploadMonitorService::class.java.canonicalName}.increment"
        private val ACTION_DECREMENT =
            "${BackgroundUploadMonitorService::class.java.canonicalName}.decrement"

        fun createIncrementIntent(context: Context) =
            Intent(context, BackgroundUploadMonitorService::class.java).apply {
                action = ACTION_INCREMENT
            }

        fun createDecrementIntent(context: Context) =
            Intent(context, BackgroundUploadMonitorService::class.java).apply {
                action = ACTION_DECREMENT
            }

        private fun getFdPosAndSize(fd: Int): Pair<Long, Long> =
            // We dup() the fd to ensure that the pos and size at least refer to the same file.
            ParcelFileDescriptor.fromFd(fd).use { pfd ->
                val pos = Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
                val size = pfd.statSize

                pos to size
            }
    }

    private lateinit var notifications: Notifications
    private var backgroundUploads = 0
    private lateinit var dataDataDir: File
    private lateinit var vfsCacheDir: File
    private val monitorThread = Thread(::monitorVfsCache)
    @Volatile
    private var monitorCanRun = false
    private var monitorNotify = false
    private var monitorProgress = 0L to 0L
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
        monitorCanRun = true
    }

    override fun onDestroy() {
        super.onDestroy()

        monitorCanRun = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        when (intent?.action) {
            ACTION_INCREMENT -> backgroundUploads += 1
            ACTION_DECREMENT -> backgroundUploads -= 1
        }

        monitorNotify = backgroundUploads != 0

        if (backgroundUploads == 0) {
            // We'll defer the stopping of the service by a small amount of time to avoid having
            // notifications rapidly appear and disappear when writing many small files. We can't
            // use FOREGROUND_SERVICE_DEFERRED because that is ignored if a deferral has already
            // happened recently.
            handler.postDelayed(stopNowRunnable, 1000)
        } else {
            handler.removeCallbacks(stopNowRunnable)
            updateForegroundNotification()
        }

        return START_NOT_STICKY
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @UiThread
    private fun updateForegroundNotification() {
        val notification = notifications.createBackgroundUploadsNotification(
            backgroundUploads,
            monitorProgress.first,
            monitorProgress.second,
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, Notifications.ID_BACKGROUND_UPLOADS, notification, type)
    }

    private fun guessVfsCacheProgress(): Pair<Long, Long> {
        val procfsFd = File("/proc/self/fd")

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

            totalPos += filePos
            totalSize += fileSize
        }

        return totalPos to totalSize
    }

    private fun monitorVfsCache() {
        while (monitorCanRun) {
            val progress = guessVfsCacheProgress()

            handler.post {
                if (monitorNotify) {
                    monitorProgress = progress
                    updateForegroundNotification()
                }
            }

            Thread.sleep(1000)
        }
    }
}
