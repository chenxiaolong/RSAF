/*
 * SPDX-FileCopyrightText: 2022-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class Notifications(private val context: Context) {
    companion object {
        private const val CHANNEL_ID_BACKGROUND_UPLOADS = "background_uploads"
        private const val CHANNEL_ID_FAILURE = "failure"

        private val LEGACY_CHANNEL_IDS = arrayOf<String>()

        const val ID_BACKGROUND_UPLOADS = 1
    }

    private val prefs = Preferences(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Create a low priority notification channel for the background uploads notification. */
    private fun createBackgroundUploadsChannel() = NotificationChannel(
        CHANNEL_ID_BACKGROUND_UPLOADS,
        context.getString(R.string.notification_channel_background_uploads_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_background_uploads_desc)
    }

    /** Create a high priority notification channel for failure alerts. */
    private fun createFailureAlertsChannel() = NotificationChannel(
        CHANNEL_ID_FAILURE,
        context.getString(R.string.notification_channel_failure_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_failure_desc)
    }

    /**
     * Ensure notification channels are up-to-date.
     *
     * Legacy notification channels are deleted without migrating settings.
     */
    fun updateChannels() {
        notificationManager.createNotificationChannels(listOf(
            createBackgroundUploadsChannel(),
            createFailureAlertsChannel(),
        ))
        LEGACY_CHANNEL_IDS.forEach { notificationManager.deleteNotificationChannel(it) }
    }

    fun createBackgroundUploadsNotification(count: Int): Notification {
        val title = context.resources.getQuantityString(
            R.plurals.notification_background_uploads_in_progress_title,
            count,
            count,
        )

        return Notification.Builder(context, CHANNEL_ID_BACKGROUND_UPLOADS).run {
            setContentTitle(title)
            setSmallIcon(R.drawable.ic_notifications)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)

            // Inhibit 10-second delay when showing persistent notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }
    }

    fun notifyBackgroundUploadFailed(documentId: String, errorMsg: String) {
        val notificationId = prefs.nextNotificationId

        val notification = Notification.Builder(context, CHANNEL_ID_FAILURE).run {
            val text = buildString {
                val errorMsgTrimmed = errorMsg.trim()
                if (errorMsgTrimmed.isNotBlank()) {
                    append(errorMsgTrimmed)
                }
                append("\n\n")
                append(documentId)
            }

            setContentTitle(context.getString(R.string.notification_background_upload_failed_title))
            if (text.isNotBlank()) {
                setContentText(text)
                style = Notification.BigTextStyle().bigText(text)
            }
            setSmallIcon(R.drawable.ic_notifications)

            build()
        }

        notificationManager.notify(notificationId, notification)
    }
}
