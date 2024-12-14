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
import android.util.Log
import androidx.annotation.UiThread
import androidx.core.app.ServiceCompat
import com.chiller3.rsaf.Notifications

/**
 * A dumb service to keep the process alive while files are open because ContentProviders don't get
 * special treatment, even when another process is actively using a file descriptor from it.
 */
class OpenFilesService : Service() {
    companion object {
        private val TAG = OpenFilesService::class.java.simpleName

        private val ACTION_INCREMENT = "${OpenFilesService::class.java.canonicalName}.increment"
        private val ACTION_DECREMENT = "${OpenFilesService::class.java.canonicalName}.decrement"

        fun createIncrementIntent(context: Context) =
            Intent(context, OpenFilesService::class.java).apply {
                action = ACTION_INCREMENT
            }

        fun createDecrementIntent(context: Context) =
            Intent(context, OpenFilesService::class.java).apply {
                action = ACTION_DECREMENT
            }
    }

    private lateinit var notifications: Notifications
    private var openFiles = 0
    private val handler = Handler(Looper.getMainLooper())
    // We need to keep a reference the same runnable for cancelling a delayed execution.
    private val stopNowRunnable = Runnable(::stopNow)

    override fun onCreate() {
        super.onCreate()

        notifications = Notifications(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        when (intent?.action) {
            ACTION_INCREMENT -> openFiles += 1
            ACTION_DECREMENT -> openFiles -= 1
        }

        if (openFiles == 0) {
            // We'll defer the stopping of the service by a small amount of time to avoid having
            // notifications rapidly appear and disappear when opening many small files. We can't
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
        val notification = notifications.createOpenFilesNotification(openFiles)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, Notifications.ID_OPEN_FILES, notification, type)
    }
}
