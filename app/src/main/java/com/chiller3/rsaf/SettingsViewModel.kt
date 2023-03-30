package com.chiller3.rsaf

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Alert {
    val requireNotifyRootsChanged: Boolean
}

data class ListRemotesFailed(val error: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
data class RemoteAddSucceeded(val remote: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteAddPartiallySucceeded(val remote: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteEditSucceeded(val remote: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
data class RemoteDeleteSucceeded(val remote: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteDeleteFailed(val remote: String, val error: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteRenameSucceeded(val oldRemote: String, val newRemote: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteRenameFailed(val oldRemote: String, val newRemote: String, val error: String) : Alert {
    // In case the failure occurred after creating the new remote and before deleting the old one
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteDuplicateSucceeded(val oldRemote: String, val newRemote: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
data class RemoteDuplicateFailed(val oldRemote: String, val newRemote: String, val error: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
object ImportSucceeded : Alert {
    override val requireNotifyRootsChanged: Boolean = true
}
object ExportSucceeded : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
data class ImportFailed(val error: String) : Alert {
    // In case reloading the original config didn't work
    override val requireNotifyRootsChanged: Boolean = true
}
data class ExportFailed(val error: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
object ImportCancelled : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
object ExportCancelled : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
data class LogcatSucceeded(val uri: Uri) : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}
data class LogcatFailed(val uri: Uri, val error: String) : Alert {
    override val requireNotifyRootsChanged: Boolean = false
}

data class Remote(
    val name: String,
    val config: Map<String, String>,
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

class SettingsViewModel : ViewModel() {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName
    }

    private val _remotes = MutableStateFlow<List<Remote>>(emptyList())
    val remotes: StateFlow<List<Remote>> = _remotes

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    private val _importExportState = MutableStateFlow<ImportExportState?>(null)
    val importExportState: StateFlow<ImportExportState?> = _importExportState

    init {
        refreshRemotes()
    }

    private suspend fun refreshRemotesInternal() {
        try {
            val r = withContext(Dispatchers.IO) {
                val providers = RcloneRpc.providers

                RcloneRpc.remotes.map {
                    Remote(it.key, it.value, providers[it.value["type"]])
                }.sortedBy { it.name }
            }

            _remotes.update { r }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh remotes", e)
            _alerts.update { it + ListRemotesFailed(e.toString()) }
        }
    }

    private fun refreshRemotes() {
        viewModelScope.launch {
            refreshRemotesInternal()
        }
    }

    fun deleteRemote(remote: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneRpc.deleteRemote(remote)
                }
                refreshRemotesInternal()
                _alerts.update { it + RemoteDeleteSucceeded(remote) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete remote $remote", e)
                _alerts.update { it + RemoteDeleteFailed(remote, e.toString()) }
            }
        }
    }

    private fun copyRemote(oldRemote: String, newRemote: String, delete: Boolean) {
        if (oldRemote == newRemote) {
            throw IllegalStateException("Old and new remote names are the same")
        }

        val (success, failure) = if (delete) {
            Pair(::RemoteRenameSucceeded, ::RemoteRenameFailed)
        } else {
            Pair(::RemoteDuplicateSucceeded, ::RemoteDuplicateFailed)
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RcloneConfig.copyRemote(oldRemote, newRemote)
                    if (delete) {
                        RcloneRpc.deleteRemote(oldRemote)
                    }
                }
                refreshRemotesInternal()
                _alerts.update { it + success(oldRemote, newRemote) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename remote $oldRemote to $newRemote", e)
                _alerts.update { it + failure(oldRemote, newRemote, e.toString()) }
            }
        }
    }

    fun renameRemote(oldRemote: String, newRemote: String) {
        copyRemote(oldRemote, newRemote, true)
    }

    fun duplicateRemote(oldRemote: String, newRemote: String) {
        copyRemote(oldRemote, newRemote, false)
    }

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
            ImportExportMode.IMPORT -> ImportCancelled
            ImportExportMode.EXPORT -> ExportCancelled
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
            ImportExportMode.IMPORT ->
                Triple(RcloneConfig::importConfigurationUri, ImportSucceeded, ::ImportFailed)
            ImportExportMode.EXPORT ->
                Triple(RcloneConfig::exportConfigurationUri, ExportSucceeded, ::ExportFailed)
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    operation(state.uri, state.password ?: "")
                }

                _alerts.update { it + success }
                _importExportState.update { null }

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
                _alerts.update { it + failure(e.toString()) }
                _importExportState.update { null }
            }
        }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun interactiveConfigurationCompleted(remote: String, new: Boolean, cancelled: Boolean) {
        viewModelScope.launch {
            refreshRemotesInternal()

            if (new) {
                if (cancelled) {
                    if (remotes.value.any { it.name == remote }) {
                        _alerts.update { it + RemoteAddPartiallySucceeded(remote) }
                    } else {
                        // No need to notify if cancelled prior to the remote being created
                    }
                } else {
                    _alerts.update { it + RemoteAddSucceeded(remote) }
                }
            } else {
                _alerts.update { it + RemoteEditSucceeded(remote) }
            }
        }
    }

    fun saveLogs(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Logcat.dump(uri)
                }
                _alerts.update { it + LogcatSucceeded(uri) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dump logs to $uri", e)
                _alerts.update { it + LogcatFailed(uri, e.toString()) }
            }
        }
    }
}