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
    }

    private sealed interface MonitorState {
        data object Inactive : MonitorState

        data object Active : MonitorState

        data class StopAfter(val remain: Int) : MonitorState

        data object Stopped : MonitorState
    }

    private lateinit var notifications: Notifications
    private val monitorThread = Thread(::monitorVfsCache)
    private var monitorState: MonitorState = MonitorState.Inactive
        set(state) {
            Log.d(TAG, "New monitor state: $state")
            field = state
        }
    private var monitorProgress = VfsCache.Progress(0, 0L, 0L)
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

    private fun monitorVfsCache() {
        while (true) {
            val scan = synchronized(monitorThread) {
                val scan = monitorScanRemotes
                monitorScanRemotes = false
                scan
            }
            if (scan) {
                VfsCache.initDirtyRemotes()
            }

            val progress = VfsCache.guessProgress(null, true)

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
