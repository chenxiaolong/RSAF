/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

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
    }

    private lateinit var notifications: Notifications
    private val backgroundUploads = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()

        notifications = Notifications(this)
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

        // This service literally does nothing. It just keeps the process alive for rclone.
        if (backgroundUploads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } else {
            val notification = notifications.createBackgroundUploadsNotification(backgroundUploads.size)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

            ServiceCompat.startForeground(this, Notifications.ID_BACKGROUND_UPLOADS, notification, type)
        }

        return START_NOT_STICKY
    }
}
