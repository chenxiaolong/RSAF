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

        private val ACTION_ADD = "${BackgroundUploadMonitorService::class.java.canonicalName}.add"
        private val ACTION_REMOVE = "${BackgroundUploadMonitorService::class.java.canonicalName}.remove"

        private const val EXTRA_DOCUMENT_ID = "document_id"

        fun createAddIntent(context: Context, documentId: String) =
            Intent(context, BackgroundUploadMonitorService::class.java).apply {
                this.action = ACTION_ADD
                putExtra(EXTRA_DOCUMENT_ID, documentId)
            }

        fun createRemoveIntent(context: Context, documentId: String) =
            Intent(context, BackgroundUploadMonitorService::class.java).apply {
                this.action = ACTION_REMOVE
                putExtra(EXTRA_DOCUMENT_ID, documentId)
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
    private val backgroundUploads = mutableSetOf<String>()
    private lateinit var dataDataDir: File
    private lateinit var vfsCacheDir: File
    private val monitorThread = Thread(::monitorVfsCache)
    @Volatile
    private var monitorCanRun = false
    private var monitorNotify = false
    private var monitorProgress = 0L to 0L
    private val handler = Handler(Looper.getMainLooper())

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
            ACTION_ADD -> {
                val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)!!
                backgroundUploads.add(documentId)
            }
            ACTION_REMOVE -> {
                val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)!!
                backgroundUploads.remove(documentId)
            }
        }

        monitorNotify = backgroundUploads.isNotEmpty()

        if (backgroundUploads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } else {
            updateForegroundNotification()
        }

        return START_NOT_STICKY
    }

    @UiThread
    private fun updateForegroundNotification() {
        val notification = notifications.createBackgroundUploadsNotification(
            backgroundUploads.size,
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
            } catch (e: ErrnoException) {
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
