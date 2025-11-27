/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.RbRemoteFeaturesResult
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.toException
import com.chiller3.rsaf.extension.toSingleLineString
import com.chiller3.rsaf.rclone.RcloneConfig
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.rclone.VfsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditRemoteActivityActions(
    val refreshRoots: Boolean = false,
    val editNewRemote: String? = null,
    val finish: Boolean = false,
)

data class RemoteState(
    val config: RcloneRpc.RemoteConfig? = null,
    val features: RbRemoteFeaturesResult? = null,
) {
    val allowExternalAccessOrDefault: Boolean?
        get() = config?.hardBlockedOrDefault?.let { !it }
    val allowLockedAccessOrDefault: Boolean?
        get() = config?.softBlockedOrDefault?.let { !it }
}

class EditRemoteViewModel : ViewModel() {
    companion object {
        private val TAG = EditRemoteViewModel::class.java.simpleName
    }

    private lateinit var _remote: String
    var remote: String
        get() = _remote
        set(value) {
            _remote = value
            refreshRemotes(false)
        }

    private val _remoteConfigs = MutableStateFlow<Map<String, RcloneRpc.RemoteConfig>>(emptyMap())
    val remoteConfigs = _remoteConfigs.asStateFlow()

    private val _remoteState = MutableStateFlow(RemoteState())
    val remoteState = _remoteState.asStateFlow()

    private val _alerts = MutableStateFlow<List<EditRemoteAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    private val _activityActions = MutableStateFlow(EditRemoteActivityActions())
    val activityActions = _activityActions.asStateFlow()

    private suspend fun refreshRemotesInternal(force: Boolean) {
        try {
            if (_remoteConfigs.value.isEmpty() || force) {
                withContext(Dispatchers.IO) {
                    _remoteConfigs.update { RcloneRpc.remoteConfigs }
                }
            }

            val config = remoteConfigs.value[remote]

            if (config != null) {
                _remoteState.update {
                    it.copy(config = config)
                }

                // Only calculate this once since the value can't change and it requires
                // initializing the backend, which may perform network calls.
                if (_remoteState.value.features == null) {
                    withContext(Dispatchers.IO) {
                        _remoteState.update {
                            val error = RbError()
                            val features = Rcbridge.rbRemoteFeatures("$remote:", error)
                                ?: throw error.toException("rbRemoteFeatures")

                            it.copy(features = features)
                        }
                    }
                }
            } else {
                // This will happen after renaming or deleting the remote.
                _remoteState.update { RemoteState() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh remotes", e)
            _alerts.update { it + EditRemoteAlert.ListRemotesFailed(e.toSingleLineString()) }
        }
    }

    private fun refreshRemotes(@Suppress("SameParameterValue") force: Boolean) {
        viewModelScope.launch {
            refreshRemotesInternal(force)
        }
    }

    val isVfsCacheDirty: Boolean
        get() = VfsCache.hasOngoingUploads(remote)

    private fun setCustomOpt(
        remote: String,
        config: RcloneRpc.RemoteConfig,
        onSuccess: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.setRemoteConfig(remote, config)
                }
                refreshRemotesInternal(true)
                onSuccess?.let { it() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set $remote config $config", e)
                // We only set one option at a time.
                val opt = config.toMap().keys.first()
                _alerts.update {
                    it + EditRemoteAlert.SetConfigFailed(remote, opt, e.toSingleLineString())
                }
            }
        }
    }

    fun setExternalAccess(allow: Boolean) {
        setCustomOpt(remote, RcloneRpc.RemoteConfig(hardBlocked = !allow)) {
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun setLockedAccess(allow: Boolean) {
        setCustomOpt(remote, RcloneRpc.RemoteConfig(softBlocked = !allow))
    }

    fun setDynamicShortcut(enabled: Boolean) {
        setCustomOpt(remote, RcloneRpc.RemoteConfig(dynamicShortcut = enabled)) {
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun setThumbnails(enabled: Boolean) {
        setCustomOpt(remote, RcloneRpc.RemoteConfig(thumbnails = enabled))
    }

    fun setReportUsage(enabled: Boolean) {
        setCustomOpt(remote, RcloneRpc.RemoteConfig(reportUsage = enabled)) {
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    private fun copyRemote(newRemote: String, delete: Boolean) {
        if (remote == newRemote) {
            throw IllegalStateException("Old and new remote names are the same")
        }

        val failure = if (delete) {
            EditRemoteAlert::RemoteRenameFailed
        } else {
            EditRemoteAlert::RemoteDuplicateFailed
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneConfig.copyRemote(remote, newRemote)
                    if (delete) {
                        RcloneRpc.deleteRemote(remote)
                    }
                }
                refreshRemotesInternal(true)
                _activityActions.update {
                    it.copy(
                        refreshRoots = true,
                        editNewRemote = newRemote,
                        finish = true,
                    )
                }
            } catch (e: Exception) {
                val action = if (delete) { "rename" } else { "duplicate" }
                Log.e(TAG, "Failed to $action remote $remote to $newRemote", e)
                _alerts.update { it + failure(remote, newRemote, e.toSingleLineString()) }
            }
        }
    }

    fun renameRemote(newRemote: String) {
        copyRemote(newRemote, true)
    }

    fun duplicateRemote(newRemote: String) {
        copyRemote(newRemote, false)
    }

    fun deleteRemote() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.deleteRemote(remote)
                }
                refreshRemotesInternal(true)
                _activityActions.update { it.copy(refreshRoots = true, finish = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete remote $remote", e)
                _alerts.update {
                    it + EditRemoteAlert.RemoteDeleteFailed(remote, e.toSingleLineString())
                }
            }
        }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun interactiveConfigurationCompleted(remote: String) {
        viewModelScope.launch {
            refreshRemotesInternal(true)
            _alerts.update { it + EditRemoteAlert.RemoteEditSucceeded(remote) }
        }
    }

    fun activityActionCompleted() {
        _activityActions.update { EditRemoteActivityActions() }
    }
}
