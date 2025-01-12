/*
 * SPDX-FileCopyrightText: 2022-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import com.chiller3.rsaf.rclone.BackgroundUploadMonitorService
import kotlin.math.roundToInt

class Notifications(private val context: Context) {
    companion object {
        private const val CHANNEL_ID_OPEN_FILES = "open_files"
        private const val CHANNEL_ID_BACKGROUND_UPLOADS = "background_uploads"
        private const val CHANNEL_ID_FAILURE = "failure"

        private val LEGACY_CHANNEL_IDS = arrayOf<String>()

        const val ID_OPEN_FILES = -1
        const val ID_BACKGROUND_UPLOADS = -2
    }

    private val prefs = Preferences(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Create a low priority notification channel for the open files notification. */
    private fun createOpenFilesChannel() = NotificationChannel(
        CHANNEL_ID_OPEN_FILES,
        context.getString(R.string.notification_channel_open_files_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_open_files_desc)
    }

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
            createOpenFilesChannel(),
            createBackgroundUploadsChannel(),
            createFailureAlertsChannel(),
        ))
        LEGACY_CHANNEL_IDS.forEach { notificationManager.deleteNotificationChannel(it) }
    }

    fun createOpenFilesNotification(count: Int): Notification {
        val title = context.resources.getQuantityString(
            R.plurals.notification_open_files_title,
            count,
            count,
        )

        return Notification.Builder(context, CHANNEL_ID_OPEN_FILES).run {
            setContentTitle(title)
            setSmallIcon(R.drawable.ic_notifications)
            setOngoing(true)
            setOnlyAlertOnce(true)

            // Inhibit 10-second delay when showing persistent notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }
    }

    fun createBackgroundUploadsNotification(
        progress: BackgroundUploadMonitorService.Progress,
    ): Notification {
        val title = context.resources.getQuantityString(
            R.plurals.notification_background_uploads_in_progress_title,
            progress.count,
            progress.count,
        )

        return Notification.Builder(context, CHANNEL_ID_BACKGROUND_UPLOADS).run {
            setContentTitle(title)
            setSmallIcon(R.drawable.ic_notifications)
            setOngoing(true)
            setOnlyAlertOnce(true)

            val formattedBytesCurrent = Formatter.formatFileSize(context, progress.bytesCurrent)
            val formattedBytesTotal = Formatter.formatFileSize(context, progress.bytesTotal)
            setContentText("$formattedBytesCurrent / $formattedBytesTotal")

            val normalizedBytesTotal = 1000
            val normalizedBytesCurrent = if (progress.bytesTotal == 0L) {
                0
            } else {
                (progress.bytesCurrent.toDouble() / progress.bytesTotal * normalizedBytesTotal)
                    .roundToInt()
            }
            setProgress(normalizedBytesTotal, normalizedBytesCurrent, progress.bytesTotal == 0L)

            // Inhibit 10-second delay when showing persistent notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }
    }
}
