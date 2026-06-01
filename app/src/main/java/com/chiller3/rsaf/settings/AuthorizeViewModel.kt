/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.rsaf.rclone.Authorizer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthorizeViewModel : ViewModel(), Authorizer.AuthorizeListener {
    companion object {
        private val TAG = AuthorizeViewModel::class.java.simpleName
    }

    private var started = false

    private val _url = MutableStateFlow<String?>(null)
    val url = _url.asStateFlow()

    private val _code = MutableStateFlow<String?>(null)
    val code = _code.asStateFlow()

    fun authorize(cmd: String) {
        if (started) {
            return
        } else {
            started = true
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Authorizer.authorizeBlocking(cmd, this@AuthorizeViewModel)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to run authorizer", e)
                _code.update { "" }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun cancel() {
        // This intentionally does not use viewModelScope. viewModelScope will not run any more
        // coroutines during onCleared(). The authorizer is a global resource and this cancellation
        // must happen or else it will be unusable for the life of the process.
        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                Authorizer.cancel()
            }
        }
    }

    override fun onCleared() {
        cancel()
    }

    override fun onAuthorizeUrl(url: String) {
        _url.update { url }
    }

    override fun onAuthorizeCode(code: String) {
        _code.update { code }
    }
}
