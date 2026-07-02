/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun rememberAuthorizeWatcher(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    listener: Authorizer.AuthorizeListener,
): AuthorizeWatcher {
    val context = LocalContext.current
    val watcher = remember(lifecycleOwner) { AuthorizeWatcher(listener) }

    DisposableEffect(lifecycleOwner) {
        watcher.bind(context)

        onDispose {
            watcher.unbind(context)
        }
    }

    return watcher
}

class AuthorizeWatcher(
    private val listener: Authorizer.AuthorizeListener,
) : ServiceConnection, Authorizer.AuthorizeListener {
    private var binder: AuthorizeService.ServiceBinder? = null
    private val handler = Handler(Looper.getMainLooper())

    internal fun bind(context: Context) {
        context.bindService(
            AuthorizeService.createBindIntent(context),
            this,
            Context.BIND_AUTO_CREATE,
        )
    }

    internal fun unbind(context: Context) {
        onBinderGone()
        context.unbindService(this)

        handler.removeCallbacksAndMessages(null)
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val serviceBinder = (service as AuthorizeService.ServiceBinder)
        serviceBinder.registerListener(this)
        binder = serviceBinder
    }

    override fun onServiceDisconnected(name: ComponentName) {
        onBinderGone()
    }

    private fun onBinderGone() {
        binder?.unregisterListener(this)
        binder = null
    }

    override fun onAuthorizeUrl(url: String) {
        handler.post { listener.onAuthorizeUrl(url) }
    }

    override fun onAuthorizeCode(code: String) {
        handler.post { listener.onAuthorizeCode(code) }
    }
}
