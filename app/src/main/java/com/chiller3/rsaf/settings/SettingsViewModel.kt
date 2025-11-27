/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.rsaf.Logcat
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
import java.io.File

data class Remote(
    val name: String,
    val provider: RcloneRpc.Provider?,
)

enum class ImportExportMode {
    IMPORT,
    EXPORT,
}

data class ImportExportState(
    val mode: ImportExportMode,
    val uri: Uri,
    val password: String?,
    val status: Status,
) {
    enum class Status {
        NEED_PASSWORD,
        IN_PROGRESS,
    }
}

data class SettingsActivityActions(
    val refreshRoots: Boolean,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName

        private const val INTERNAL_CACHE_REMOTE_NAME = "rclone_internal_cache"
    }

    private val _remotes = MutableStateFlow<List<Remote>>(emptyList())
    val remotes = _remotes.asStateFlow()

    private val _alerts = MutableStateFlow<List<SettingsAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    private val _importExportState = MutableStateFlow<ImportExportState?>(null)
    val importExportState = _importExportState.asStateFlow()

    private val _activityActions = MutableStateFlow(SettingsActivityActions(false))
    val activityActions = _activityActions.asStateFlow()

    init {
        refreshRemotes()
    }

    private suspend fun refreshRemotesInternal() {
        try {
            val r = withContext(Dispatchers.IO) {
                val providers = RcloneRpc.providers

                RcloneRpc.remoteConfigsRaw.map {
                    Remote(it.key, providers[it.value["type"]])
                }.sortedBy { it.name }
            }

            _remotes.update { r }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh remotes", e)
            _alerts.update { it + SettingsAlert.ListRemotesFailed(e.toSingleLineString()) }
        }
    }

    private fun refreshRemotes() {
        viewModelScope.launch {
            refreshRemotesInternal()
        }
    }

    fun remoteEdited() {
        refreshRemotes()
    }

    val isAnyVfsCacheDirty: Boolean
        get() = VfsCache.hasOngoingUploads(null)

    fun startImportExport(mode: ImportExportMode, uri: Uri) {
        if (importExportState.value != null) {
            throw IllegalStateException("Import/export already started")
        }

        // Prompt for password immediately when exporting
        val status = when (mode) {
            ImportExportMode.IMPORT -> ImportExportState.Status.IN_PROGRESS
            ImportExportMode.EXPORT -> ImportExportState.Status.NEED_PASSWORD
        }

        _importExportState.update { ImportExportState(mode, uri, null, status) }

        if (status == ImportExportState.Status.IN_PROGRESS) {
            performImportExport()
        }
    }

    fun setImportExportPassword(password: String) {
        if (importExportState.value == null) {
            throw IllegalStateException("Import/export not started")
        }

        _importExportState.update {
            it!!.copy(
                password = password,
                status = ImportExportState.Status.IN_PROGRESS,
            )
        }
        performImportExport()
    }

    fun cancelPendingImportExport() {
        val state = importExportState.value
            ?: throw IllegalStateException("Import/export not started")
        if (state.status == ImportExportState.Status.IN_PROGRESS) {
            throw IllegalStateException("Cannot cancel in progress import/export")
        }

        val alert = when (state.mode) {
            ImportExportMode.IMPORT -> SettingsAlert.ImportCancelled
            ImportExportMode.EXPORT -> SettingsAlert.ExportCancelled
        }

        _alerts.update { it + alert }
        _importExportState.update { null }
    }

    private fun performImportExport() {
        val state = importExportState.value
            ?: throw IllegalStateException("Import/export not started")
        if (state.status != ImportExportState.Status.IN_PROGRESS) {
            throw IllegalStateException("Import/export status is not in progress")
        }

        val (operation, success, failure) = when (state.mode) {
            ImportExportMode.IMPORT -> Triple(
                RcloneConfig::importConfigurationUri,
                SettingsAlert.ImportSucceeded,
                SettingsAlert::ImportFailed,
            )
            ImportExportMode.EXPORT -> Triple(
                RcloneConfig::exportConfigurationUri,
                SettingsAlert.ExportSucceeded,
                SettingsAlert::ExportFailed,
            )
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    operation(state.uri, state.password ?: "")
                }

                _alerts.update { it + success }
                _importExportState.update { null }
                _activityActions.update { it.copy(refreshRoots = true) }

                if (state.mode == ImportExportMode.IMPORT) {
                    refreshRemotes()
                }
            } catch (e: RcloneConfig.BadPasswordException) {
                Log.w(TAG, "Incorrect password", e)
                _importExportState.update {
                    it!!.copy(status = ImportExportState.Status.NEED_PASSWORD)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform import/export", e)
                _alerts.update { it + failure(e.toSingleLineString()) }
                _importExportState.update { null }

                if (state.mode == ImportExportMode.IMPORT) {
                    // In case reloading the original config didn't work.
                    _activityActions.update { it.copy(refreshRoots = true) }
                }
            }
        }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun interactiveConfigurationCompleted(remote: String, cancelled: Boolean) {
        viewModelScope.launch {
            refreshRemotesInternal()

            if (cancelled) {
                if (remotes.value.any { it.name == remote }) {
                    _alerts.update { it + SettingsAlert.RemoteAddPartiallySucceeded(remote) }
                } else {
                    // No need to notify if cancelled prior to the remote being created
                }
            } else {
                _alerts.update { it + SettingsAlert.RemoteAddSucceeded(remote) }
            }

            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun saveLogs(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Logcat.dump(uri)
                }
                _alerts.update { it + SettingsAlert.LogcatSucceeded(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dump logs to $uri", e)
                _alerts.update { it + SettingsAlert.LogcatFailed(uri, e.toSingleLineString()) }
            }
        }
    }

    fun addInternalCacheRemote() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cacheDir = File(getApplication<Application>().cacheDir, "rclone").path

                val iq = RcloneRpc.InteractiveConfiguration(INTERNAL_CACHE_REMOTE_NAME)
                while (true) {
                    val (_, option) = iq.question ?: break

                    when (option.name) {
                        "type" -> iq.submit("alias")
                        "remote" -> iq.submit(cacheDir)
                        "config_fs_advanced" -> iq.submit("false")
                        else -> throw IllegalStateException("Unexpected question: ${option.name}")
                    }
                }
            }

            refreshRemotesInternal()
            _alerts.update { it + SettingsAlert.RemoteAddSucceeded(INTERNAL_CACHE_REMOTE_NAME) }
            _activityActions.update { it.copy(refreshRoots = true) }
        }
    }

    fun activityActionCompleted() {
        _activityActions.update { SettingsActivityActions(false) }
    }
}
