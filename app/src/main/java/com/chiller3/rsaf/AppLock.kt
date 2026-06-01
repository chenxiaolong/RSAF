/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log

object AppLock {
    private sealed interface State {
        val isLocked: Boolean

        data object Active : State {
            override val isLocked: Boolean = false
        }

        data class Inactive(val pauseTime: Long) : State {
            constructor() : this(SystemClock.elapsedRealtimeNanos())

            override val isLocked: Boolean
                get() = SystemClock.elapsedRealtimeNanos() - pauseTime >=
                        prefs.inactivityTimeout * 1_000_000_000L
        }

        data object Locked : State {
            override val isLocked: Boolean = true
        }
    }

    private val TAG = AppLock::class.java.simpleName

    private lateinit var appContext: Context
    @SuppressLint("StaticFieldLeak")
    private lateinit var prefs: Preferences
    private var state: State = State.Locked
        set(s) {
            Log.d(TAG, "State changed: $s")
            field = s
        }

    val isLocked: Boolean
        get() = state.isLocked

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Preferences(appContext)

        if (!prefs.requireAuth) {
            state = State.Active
        }
    }

    fun onAppResume() {
        state.let {
            if (it is State.Inactive) {
                if (it.isLocked) {
                    Log.d(TAG, "Timed out due to inactivity")
                    state = State.Locked
                } else {
                    Log.d(TAG, "App is active again")
                    state = State.Active
                }
            }
        }
    }

    fun onAppPause() {
        if (prefs.requireAuth && state == State.Active) {
            Log.d(TAG, "App is inactive")
            state = State.Inactive()
        }
    }

    fun onAuthSuccess() {
        Log.d(TAG, "Authentication succeeded")
        state = State.Active
    }

    fun onLock() {
        if (prefs.requireAuth) {
            Log.d(TAG, "User requested immediate locking")
            state = State.Locked
        }
    }
}
