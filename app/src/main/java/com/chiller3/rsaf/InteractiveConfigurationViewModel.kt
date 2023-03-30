package com.chiller3.rsaf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InteractiveConfigurationViewModel : ViewModel() {
    private lateinit var ic: RcloneRpc.InteractiveConfiguration

    private val _question = MutableStateFlow<Pair<String?, RcloneRpc.ProviderOption>?>(null)
    val question: StateFlow<Pair<String?, RcloneRpc.ProviderOption>?> = _question

    private val _run = MutableStateFlow(true)
    val run: StateFlow<Boolean> = _run

    fun init(remote: String) {
        viewModelScope.launch {
            ic = withContext(Dispatchers.IO) {
                RcloneRpc.InteractiveConfiguration(remote)
            }
            _question.update { ic.question }
        }
    }

    fun submit(result: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ic.submit(result)
            }
            _question.update { ic.question }

            if (ic.question == null) {
                _run.update { false }
            }
        }
    }
}