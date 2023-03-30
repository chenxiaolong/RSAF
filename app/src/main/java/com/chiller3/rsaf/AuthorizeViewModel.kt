package com.chiller3.rsaf

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthorizeViewModel : ViewModel(), Authorizer.AuthorizeListener {
    companion object {
        private val TAG = AuthorizeViewModel::class.java.simpleName
    }

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url

    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code

    fun authorize(cmd: String) {
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

    fun cancel() {
        viewModelScope.launch {
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