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
import android.util.Log
import androidx.annotation.UiThread
import androidx.core.app.ServiceCompat
import com.chiller3.rsaf.Notifications
import com.chiller3.rsaf.binding.rcbridge.Rcbridge

/**
 * A dumb service to keep the process alive while files are open because ContentProviders don't get
 * special treatment, even when another process is actively using a file descriptor from it.
 *
 * This also polls the VFS cache to keep the service alive during background uploads + an additional
 * period to allow for VFS cache cleanup to occur.
 */
class KeepAliveService : Service() {
    companion object {
        private val TAG = KeepAliveService::class.java.simpleName

        private const val EXTRA_ADJ_OPEN = "adj_open"
        private const val EXTRA_ADJ_UPLOADING = "adj_uploading"
        private const val EXTRA_SCAN_REMOTES = "scan_remotes"

        private var startedWithScanOnce = false

        fun createIntent(context: Context, adjOpen: Int, adjUploading: Int, scanRemotes: Boolean) =
            Intent(context, KeepAliveService::class.java).apply {
                putExtra(EXTRA_ADJ_OPEN, adjOpen)
                putExtra(EXTRA_ADJ_UPLOADING, adjUploading)
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
                context.startForegroundService(createIntent(context, 0, 0, true))
            }
        }
    }

    data class State(
        val open: Int,
        val syncUploading: Int,
        val asyncUploading: Int,
        val asyncPending: Int,
    ) {
        val total = open + syncUploading + asyncUploading + asyncPending
    }

    private enum class MonitorState {
        RUNNING,
        STOPPING,
        STOPPED,
    }

    private lateinit var notifications: Notifications
    private var state = State(0, 0, 0, 0)
        set(value) {
            field = value
            Log.d(TAG, "State updated: $value")
        }
    private var updatedOnce = false

    private val monitorThread = Thread(::monitorAsyncWriteback)
    private var monitorState = MonitorState.RUNNING
    private var monitorScanRemotes = false

    private val handler = Handler(Looper.getMainLooper())
    // We need to keep a reference the same runnable for cancelling a delayed execution.
    private val stopNowRunnable = Runnable(::stopNow)

    override fun onCreate() {
        super.onCreate()

        notifications = Notifications(this)

        monitorThread.start()
    }

    override fun onDestroy() {
        super.onDestroy()

        synchronized(monitorThread) {
            monitorState = MonitorState.STOPPED
            (monitorThread as Object).notify()
        }

        Log.d(TAG, "Exiting")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        val adjOpen = intent!!.getIntExtra(EXTRA_ADJ_OPEN, 0)
        val adjUploading = intent.getIntExtra(EXTRA_ADJ_UPLOADING, 0)

        synchronized(monitorThread) {
            monitorScanRemotes = intent.getBooleanExtra(EXTRA_SCAN_REMOTES, false)
        }

        updateState(
            state.copy(
                open = state.open + adjOpen,
                syncUploading = state.syncUploading + adjUploading,
            ),
            !updatedOnce,
        )
        updatedOnce = true

        return START_NOT_STICKY
    }

    private fun stopNow() {
        synchronized(monitorThread) {
            monitorState = MonitorState.STOPPING
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(newState: State, force: Boolean) {
        if (newState == state && !force) {
            return
        }

        state = newState

        handler.removeCallbacks(stopNowRunnable)

        if (newState.total == 0) {
            // The additional buffer is for the actual execution of the cache cleanup in case we hit
            // the worst case where it doesn't begin executing until the very end of the window.
            val waitFor = Rcbridge.rbCacheCleanupMaxWaitSeconds() + 5

            Log.d(TAG, "Delayed exit in $waitFor seconds")
            handler.postDelayed(stopNowRunnable, waitFor * 1000)
        }

        updateForegroundNotification(newState)
    }

    @UiThread
    private fun updateForegroundNotification(newState: State) {
        val notification = notifications.createKeepAliveNotification(newState)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, Notifications.ID_KEEP_ALIVE, notification, type)
    }

    // We generally don't allow async writeback, except when resuming incomplete uploads after the
    // app is restarted. This is super ugly and relies on polling because there is no API, internal
    // or external, to wait for writeback in a saner way. For sync writeback, we just rely on
    // RcloneProvider updating our counters.
    private fun monitorAsyncWriteback() {
        while (true) {
            val scan = synchronized(monitorThread) {
                if (monitorState == MonitorState.STOPPED) {
                    break
                }

                val scan = monitorScanRemotes
                monitorScanRemotes = false
                scan
            }
            if (scan) {
                VfsCache.initDirtyRemotes()
            }

            val progress = VfsCache.asyncUploadProgress(null)

            synchronized(monitorThread) {
                if (monitorState == MonitorState.RUNNING) {
                    handler.post {
                        updateState(
                            state.copy(
                                asyncUploading = progress.uploading,
                                asyncPending = progress.pending,
                            ),
                            false,
                        )
                    }
                }

                (monitorThread as Object).wait(1000)
            }
        }
    }
}
