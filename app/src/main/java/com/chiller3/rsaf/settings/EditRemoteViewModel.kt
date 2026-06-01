/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class EditRemoteActivityActions(
    val refreshRoots: Boolean = false,
    val editNewRemote: String? = null,
    val finish: Boolean = false,
)

data class RemoteState(
    val config: RcloneRpc.RemoteConfig? = null,
    val features: RbRemoteFeaturesResult? = null,
)

class EditRemoteViewModel : ViewModel() {
    companion object {
        private val TAG = EditRemoteViewModel::class.java.simpleName
    }

    private val mainLock = this
    private val operationLock = Mutex()

    private lateinit var _remote: String
    private var refreshJob: Job? = null

    private val _remoteConfigs = MutableStateFlow<Map<String, RcloneRpc.RemoteConfig>>(emptyMap())
    val remoteConfigs = _remoteConfigs.asStateFlow()

    private val _remoteState = MutableStateFlow(RemoteState())
    val remoteState = _remoteState.asStateFlow()

    private val _alerts = MutableStateFlow<List<EditRemoteAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    private val _activityActions = MutableStateFlow(EditRemoteActivityActions())
    val activityActions = _activityActions.asStateFlow()

    // We intentionally use the same viewmodel instead of creating a new one with unique keys. Old
    // viewmodels never get cleaned up until the composable is removed, so memory usage would grow
    // indefinitely if the remote was continuously renamed.
    fun init(newRemote: String) {
        synchronized(mainLock) {
            if (!::_remote.isInitialized || _remote != newRemote) {
                _remote = newRemote
                _remoteState.update { RemoteState() }

                // Refreshes after running.
                launchOperation {}
            }
        }
    }

    private fun currentRemote() = synchronized(mainLock) { _remote }

    private fun launchOperation(block: suspend CoroutineScope.(String) -> Unit): Job {
        // Synchronously obtain the current remote because it can change while the operation is
        // running.
        val remote = currentRemote()

        // We always allow refreshes to be cancelled because it is more important that the user's
        // operations are processed quickly. We'll refresh again afterwards anyway.
        synchronized(mainLock) {
            refreshJob?.let {
                Log.d(TAG, "Cancelling existing refresh job: $it")
                it.cancel()
            }
        }

        return viewModelScope.launch {
            operationLock.withLock {
                block(remote)

                val job = launch {
                    refreshRemotesLocked()
                }

                synchronized(mainLock) {
                    refreshJob = job
                }

                try {
                    job.join()
                } finally {
                    synchronized(mainLock) {
                        refreshJob = null
                    }
                }
            }
        }
    }

    private suspend fun refreshRemotesLocked() {
        val remote = currentRemote()

        try {
            withContext(Dispatchers.IO) {
                yield()
                _remoteConfigs.update { RcloneRpc.remoteConfigs }
            }

            val config = remoteConfigs.value[remote]

            if (config != null) {
                yield()
                _remoteState.update { it.copy(config = config) }

                // Only calculate this once since the value can't change and it requires
                // initializing the backend, which may perform network calls.
                if (_remoteState.value.features == null) {
                    // This is the slowest part. We run this in a separate coroutine so that when
                    // cancelled, the current coroutine can exit quickly. There's no way to
                    // interrupt rclone during this operation.
                    val features = viewModelScope.async {
                        withContext(Dispatchers.IO) {
                            val error = RbError()
                            Rcbridge.rbRemoteFeatures("$remote:", error)
                                ?: throw error.toException("rbRemoteFeatures")
                        }
                    }.await()
                    _remoteState.update { it.copy(features = features) }
                }
            } else {
                // This will happen after renaming or deleting the remote.
                yield()
                _remoteState.update { RemoteState() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            yield()
            Log.e(TAG, "Failed to refresh remotes", e)
            _alerts.update { it + EditRemoteAlert.ListRemotesFailed(e.toSingleLineString()) }
        }
    }

    val isVfsCacheDirty: Boolean
        get() = VfsCache.hasOngoingUploads(currentRemote())

    private fun setCustomOpt(
        config: RcloneRpc.RemoteConfig,
        clearVfsCache: Boolean = false,
        onSuccess: (() -> Unit)? = null,
    ) {
        launchOperation { remote ->
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.setRemoteConfig(remote, config)

                    if (clearVfsCache) {
                        Rcbridge.rbCacheClearRemote("$remote:", false)
                    }
                }
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
        setCustomOpt(RcloneRpc.RemoteConfig(hardBlocked = !allow)) {
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun setLockedAccess(allow: Boolean) {
        setCustomOpt(RcloneRpc.RemoteConfig(softBlocked = !allow))
    }

    fun setDynamicShortcut(enabled: Boolean) {
        setCustomOpt(RcloneRpc.RemoteConfig(dynamicShortcut = enabled)) {
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun setThumbnails(enabled: Boolean) {
        setCustomOpt(RcloneRpc.RemoteConfig(thumbnails = enabled))
    }

    fun setReportUsage(enabled: Boolean) {
        setCustomOpt(RcloneRpc.RemoteConfig(reportUsage = enabled)) {
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun setVfsOptions(options: Map<String, String>, reload: Boolean) {
        setCustomOpt(RcloneRpc.RemoteConfig(vfsOptions = options), reload)
    }

    private fun copyRemote(newRemote: String, delete: Boolean) {
        launchOperation { remote ->
            if (remote == newRemote) {
                throw IllegalStateException("Old and new remote names are the same")
            }

            val failure = if (delete) {
                EditRemoteAlert::RemoteRenameFailed
            } else {
                EditRemoteAlert::RemoteDuplicateFailed
            }

            try {
                withContext(Dispatchers.IO) {
                    RcloneConfig.copyRemote(remote, newRemote)
                    if (delete) {
                        RcloneRpc.deleteRemote(remote)
                    }
                }
                _activityActions.update {
                    it.copy(
                        refreshRoots = true,
                        editNewRemote = newRemote,
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
        launchOperation { remote ->
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.deleteRemote(remote)
                }
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

    fun addAlert(alert: EditRemoteAlert) {
        _alerts.update { it + alert }
    }

    fun interactiveConfigurationCompleted(remote: String) {
        launchOperation {
            _alerts.update { it + EditRemoteAlert.RemoteEditSucceeded(remote) }
        }
    }

    fun activityActionCompleted() {
        _activityActions.update { EditRemoteActivityActions() }
    }
}
