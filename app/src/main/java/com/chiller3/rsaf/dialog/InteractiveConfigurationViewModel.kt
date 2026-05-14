/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.rsaf.rclone.RcloneRpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InteractiveConfigurationViewModel : ViewModel() {
    private lateinit var ic: RcloneRpc.InteractiveConfiguration

    private val _question = MutableStateFlow<Pair<String?, RcloneRpc.ProviderOption>?>(null)
    val question = _question.asStateFlow()

    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious = _hasPrevious.asStateFlow()

    private val _run = MutableStateFlow(true)
    val run = _run.asStateFlow()

    fun init(remote: String) {
        viewModelScope.launch {
            ic = withContext(Dispatchers.IO) {
                RcloneRpc.InteractiveConfiguration(remote)
            }

            loadQuestion()
        }
    }

    fun submit(result: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ic.submit(result)
            }

            loadQuestion()
        }
    }

    private fun loadQuestion() {
        val question = ic.question

        _question.update { question }
        _hasPrevious.update { ic.hasPrevious }

        if (question == null) {
            _run.update { false }
        }
    }

    fun goBack() {
        ic.goBack()

        loadQuestion()
    }
}
