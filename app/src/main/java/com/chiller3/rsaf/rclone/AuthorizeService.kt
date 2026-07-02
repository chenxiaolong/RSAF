/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.chiller3.rsaf.Notifications

class AuthorizeService : Service(), Authorizer.AuthorizeListener {
    companion object {
        private val TAG = AuthorizeService::class.java.simpleName

        private val ACTION_START = "${AuthorizeService::class.java.canonicalName}.start"
        private val ACTION_CANCEL = "${AuthorizeService::class.java.canonicalName}.cancel"

        private const val EXTRA_CMD = "cmd"

        fun createStartIntent(context: Context, cmd: String) =
            Intent(context, AuthorizeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CMD, cmd)
            }

        fun createCancelIntent(context: Context) =
            Intent(context, AuthorizeService::class.java).apply {
                action = ACTION_CANCEL
            }

        fun createBindIntent(context: Context) = Intent(context, AuthorizeService::class.java)
    }

    private lateinit var notifications: Notifications
    private lateinit var authorizeThread: Thread
    private var authorizeUrl: String? = null
        set(url) {
            synchronized(listeners) {
                field = url

                if (url != null) {
                    for (listener in listeners) {
                        listener.onAuthorizeUrl(url)
                    }
                }
            }
        }
    private var authorizeCode: String? = null
        set(code) {
            synchronized(listeners) {
                field = code

                if (code != null) {
                    for (listener in listeners) {
                        listener.onAuthorizeCode(code)
                    }
                }
            }
        }
    private val listeners = HashSet<Authorizer.AuthorizeListener>()
    private var cancelled = false

    override fun onCreate() {
        super.onCreate()

        notifications = Notifications(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        cancel()

        Log.d(TAG, "Exiting")
    }

    override fun onBind(intent: Intent?): IBinder = ServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        when (intent?.action) {
            ACTION_START -> {
                if (::authorizeThread.isInitialized) {
                    Log.w(TAG, "Ignoring request since service is already running")
                } else {
                    val cmd = intent.getStringExtra(EXTRA_CMD)!!

                    authorizeThread = Thread {
                        try {
                            Authorizer.authorizeBlocking(cmd, this)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to run authorizer", e)
                        } finally {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }.apply { start() }

                    val notification = notifications.createAuthorizeNotification()
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }

                    ServiceCompat.startForeground(this, Notifications.ID_AUTHORIZE, notification, type)
                }
            }
            ACTION_CANCEL -> cancel()
            else -> Log.w(TAG, "Ignoring unrecognized intent: $intent")
        }

        return START_NOT_STICKY
    }

    private fun cancel() {
        if (!cancelled) {
            // This cannot be done on the main thread because it makes a localhost HTTP request.
            Thread(Authorizer::cancel).start()
            cancelled = true
        }
    }

    override fun onAuthorizeUrl(url: String) {
        authorizeUrl = url
    }

    override fun onAuthorizeCode(code: String) {
        authorizeCode = code
    }

    inner class ServiceBinder : Binder() {
        fun registerListener(listener: Authorizer.AuthorizeListener) {
            synchronized(listeners) {
                Log.d(TAG, "Registering listener: $listener")

                if (!listeners.add(listener)) {
                    Log.w(TAG, "Listener was already registered: $listener")
                }

                authorizeUrl?.let(listener::onAuthorizeUrl)
                authorizeCode?.let(listener::onAuthorizeCode)
            }
        }

        fun unregisterListener(listener: Authorizer.AuthorizeListener) {
            synchronized(listeners) {
                Log.d(TAG, "Unregistering listener: $listener")

                if (!listeners.remove(listener)) {
                    Log.w(TAG, "Listener was never registered: $listener")
                }
            }
        }
    }
}
