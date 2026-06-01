/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneProvider
import com.chiller3.rsaf.rclone.RcloneProvider.Companion.documentsUiIntent
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.ui.AppScreen
import com.chiller3.rsaf.ui.BetterSegmentedShapes
import com.chiller3.rsaf.ui.Preference
import com.chiller3.rsaf.ui.PreferenceCategory
import com.chiller3.rsaf.ui.PreferenceColumn
import com.chiller3.rsaf.ui.SwitchPreference
import com.chiller3.rsaf.ui.theme.AppTheme

@Composable
fun EditRemoteScreen(
    remote: String,
    onEditNext: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: EditRemoteViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    viewModel.init(remote)

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val requireAuth = remember(reloadPrefs) { prefs.requireAuth }

    val remoteState by viewModel.remoteState.collectAsStateWithLifecycle()
    val remoteConfigs by viewModel.remoteConfigs.collectAsStateWithLifecycle()

    val requestInteractiveConfiguration = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val remote = it.data?.getStringExtra(InteractiveConfigurationActivity.EXTRA_REMOTE)!!

        viewModel.interactiveConfigurationCompleted(remote)
    }

    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = remote) },
        onBack = onBack,
    ) { params ->
        LaunchedEffect(Unit) {
            viewModel.alerts.collect { alerts ->
                val alert = alerts.firstOrNull() ?: return@collect
                val msg = when (alert) {
                    is EditRemoteAlert.ListRemotesFailed ->
                        resources.getString(R.string.alert_list_remotes_failure)
                    is EditRemoteAlert.RemoteEditSucceeded ->
                        resources.getString(R.string.alert_edit_remote_success, alert.remote)
                    is EditRemoteAlert.RemoteDeleteFailed ->
                        resources.getString(R.string.alert_delete_remote_failure, alert.remote)
                    is EditRemoteAlert.RemoteRenameFailed ->
                        resources.getString(R.string.alert_rename_remote_failure, alert.oldRemote, alert.newRemote)
                    is EditRemoteAlert.RemoteDuplicateFailed ->
                        resources.getString(R.string.alert_duplicate_remote_failure, alert.oldRemote, alert.newRemote)
                    is EditRemoteAlert.SetConfigFailed ->
                        resources.getString(R.string.alert_set_config_failure, alert.opt, alert.remote)
                    EditRemoteAlert.DocumentsUINotFound ->
                        resources.getString(R.string.alert_documentsui_not_found)
                }
                val details = when (alert) {
                    is EditRemoteAlert.ListRemotesFailed -> alert.error
                    is EditRemoteAlert.RemoteEditSucceeded -> null
                    is EditRemoteAlert.RemoteDeleteFailed -> alert.error
                    is EditRemoteAlert.RemoteRenameFailed -> alert.error
                    is EditRemoteAlert.RemoteDuplicateFailed -> alert.error
                    is EditRemoteAlert.SetConfigFailed -> alert.error
                    EditRemoteAlert.DocumentsUINotFound -> null
                }

                val result = params.snackbarHostState.showSnackbar(
                    message = msg,
                    details?.let { resources.getString(R.string.action_details) },
                    withDismissAction = true,
                )
                viewModel.acknowledgeFirstAlert()

                when (result) {
                    SnackbarResult.Dismissed -> {}
                    SnackbarResult.ActionPerformed -> { showErrorDialog = details }
                }
            }
        }

        showErrorDialog?.let { message ->
            ErrorDetailsDialog(
                message = message,
                onDismiss = { showErrorDialog = null },
            )
        }

        EditRemoteContent(
            remote = remote,
            state = remoteState,
            existingRemotes = remoteConfigs.keys.toList(),
            requireAuth = requireAuth,
            onRemoteOpen = {
                try {
                    context.startActivity(documentsUiIntent(remote))
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(EditRemoteAlert.DocumentsUINotFound)
                }
            },
            onRemoteConfigure = {
                requestInteractiveConfiguration.launch(
                    InteractiveConfigurationActivity.createIntent(context, remote, false)
                )
            },
            onRemoteRename = { name ->
                viewModel.renameRemote(name)
            },
            onRemoteDuplicate = { name ->
                viewModel.duplicateRemote(name)
            },
            onRemoteDelete = {
                viewModel.deleteRemote()
            },
            onAllowExternalAccessChange = { enabled ->
                viewModel.setExternalAccess(enabled)
            },
            onAllowLockedAccessChange = { enabled ->
                viewModel.setLockedAccess(enabled)
            },
            onDynamicShortcutChange = { enabled ->
                viewModel.setDynamicShortcut(enabled)
            },
            onThumbnailsChange = { enabled ->
                viewModel.setThumbnails(enabled)
            },
            onReportUsageChange = { enabled ->
                viewModel.setReportUsage(enabled)
            },
            onVfsOptionsChange = { options, reload ->
                viewModel.setVfsOptions(options, reload)
            },
            isVfsCacheDirty = {
                viewModel.isVfsCacheDirty
            },
            contentPadding = params.contentPadding,
        )
    }

    LaunchedEffect(remote) {
        viewModel.activityActions.collect {
            if (it.refreshRoots) {
                RcloneProvider.notifyRootsChanged(context)
            }
            it.editNewRemote?.let { newRemote ->
                onEditNext(newRemote)
            }
            if (it.finish) {
                onBack()
            }
            viewModel.activityActionCompleted()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditRemoteContent(
    remote: String,
    state: RemoteState,
    existingRemotes: List<String>,
    requireAuth: Boolean,
    onRemoteOpen: () -> Unit,
    onRemoteConfigure: () -> Unit,
    onRemoteRename: (String) -> Unit,
    onRemoteDuplicate: (String) -> Unit,
    onRemoteDelete: () -> Unit,
    onAllowExternalAccessChange: (Boolean) -> Unit,
    onAllowLockedAccessChange: (Boolean) -> Unit,
    onDynamicShortcutChange: (Boolean) -> Unit,
    onThumbnailsChange: (Boolean) -> Unit,
    onReportUsageChange: (Boolean) -> Unit,
    onVfsOptionsChange: (Map<String, String>, Boolean) -> Unit,
    isVfsCacheDirty: () -> Boolean,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showVfsWarningDialog by rememberSaveable { mutableStateOf<VfsCacheDeletionReason?>(null) }
    var showRemoteNameDialog by rememberSaveable { mutableStateOf<RemoteNameDialogAction?>(null) }
    var showVfsOptionsDialog by rememberSaveable { mutableStateOf(false) }

    val allowExternalAccess = state.config?.hardBlockedOrDefault == false
    val allowLockedAccess = state.config?.softBlockedOrDefault == false

    PreferenceColumn(contentPadding = contentPadding) {
        item(key = "remote") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_remote)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "open_remote") {
            Preference(
                onClick = onRemoteOpen,
                enabled = allowExternalAccess,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_edit_remote_open_name)) },
                summary = { Text(text = stringResource(R.string.pref_edit_remote_open_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "configure_remote") {
            Preference(
                onClick = onRemoteConfigure,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_edit_remote_configure_name)) },
                summary = { Text(text = stringResource(R.string.pref_edit_remote_configure_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "rename_remote") {
            Preference(
                onClick = {
                    if (isVfsCacheDirty()) {
                        showVfsWarningDialog = VfsCacheDeletionReason.Rename(remote)
                    } else {
                        showRemoteNameDialog = RemoteNameDialogAction.Rename(remote)
                    }
                },
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_edit_remote_rename_name)) },
                summary = { Text(text = stringResource(R.string.pref_edit_remote_rename_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "duplicate_remote") {
            Preference(
                onClick = { showRemoteNameDialog = RemoteNameDialogAction.Duplicate(remote) },
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_edit_remote_duplicate_name)) },
                summary = { Text(text = stringResource(R.string.pref_edit_remote_duplicate_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "delete_remote") {
            Preference(
                onClick = {
                    if (isVfsCacheDirty()) {
                        showVfsWarningDialog = VfsCacheDeletionReason.Delete(remote)
                    } else {
                        onRemoteDelete()
                    }
                },
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_edit_remote_delete_name)) },
                summary = { Text(text = stringResource(R.string.pref_edit_remote_delete_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (state.config != null) {
            item(key = "behavior") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_behavior)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "allow_external_access") {
                SwitchPreference(
                    checked = allowExternalAccess,
                    onCheckedChange = onAllowExternalAccessChange,
                    shapes = BetterSegmentedShapes.top(),
                    title = { Text(text = stringResource(R.string.pref_edit_remote_allow_external_access_name)) },
                    summary = { Text(text = stringResource(R.string.pref_edit_remote_allow_external_access_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "allow_locked_access") {
                SwitchPreference(
                    checked = allowLockedAccess,
                    onCheckedChange = onAllowLockedAccessChange,
                    enabled = allowExternalAccess && requireAuth,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_edit_remote_allow_locked_access_name)) },
                    summary = { Text(text = allowLockedAccessSummary(allowLockedAccess)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "dynamic_shortcut") {
                SwitchPreference(
                    checked = state.config.dynamicShortcutOrDefault,
                    onCheckedChange = onDynamicShortcutChange,
                    enabled = allowExternalAccess,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_edit_remote_dynamic_shortcut_name)) },
                    summary = { Text(text = stringResource(R.string.pref_edit_remote_dynamic_shortcut_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "thumbnails") {
                SwitchPreference(
                    checked = state.config.thumbnailsOrDefault,
                    onCheckedChange = onThumbnailsChange,
                    enabled = allowExternalAccess,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_edit_remote_thumbnails_name)) },
                    summary = { Text(text = stringResource(R.string.pref_edit_remote_thumbnails_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "report_usage") {
                SwitchPreference(
                    checked = state.config.reportUsageOrDefault,
                    onCheckedChange = onReportUsageChange,
                    enabled = allowExternalAccess && state.features?.about == true,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_edit_remote_report_usage_name)) },
                    summary = { Text(text = reportUsageSummary(state)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "vfs_options") {
                Preference(
                    onClick = { showVfsOptionsDialog = true },
                    enabled = allowExternalAccess,
                    shapes = BetterSegmentedShapes.bottom(),
                    title = { Text(text = stringResource(R.string.pref_edit_remote_vfs_options_name)) },
                    summary = { Text(text = stringResource(R.string.pref_edit_remote_vfs_options_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    showVfsWarningDialog?.let { reason ->
        VfsCacheDeletionDialog(
            reason = reason,
            onConfirm = {
                when (reason) {
                    is VfsCacheDeletionReason.Rename ->
                        showRemoteNameDialog = RemoteNameDialogAction.Rename(reason.remote)
                    is VfsCacheDeletionReason.Delete -> onRemoteDelete()
                    else -> throw IllegalStateException("Invalid reason: $reason")
                }

                @Suppress("AssignedValueIsNeverRead")
                showVfsWarningDialog = null
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showVfsWarningDialog = null
            }
        )
    }

    showRemoteNameDialog?.let { action ->
        RemoteNameDialog(
            action = action,
            existingRemotes = existingRemotes,
            onSelect = { name ->
                when (action) {
                    is RemoteNameDialogAction.Rename -> onRemoteRename(name)
                    is RemoteNameDialogAction.Duplicate -> onRemoteDuplicate(name)
                    else -> throw IllegalStateException("Invalid action: $action")
                }

                @Suppress("AssignedValueIsNeverRead")
                showRemoteNameDialog = null
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showRemoteNameDialog = null
            },
        )
    }

    if (showVfsOptionsDialog) {
        VfsOptionsDialog(
            remote = remote,
            initialOptions = state.config?.vfsOptions!!,
            onSelect = { options, reload ->
                onVfsOptionsChange(options, reload)
                @Suppress("AssignedValueIsNeverRead")
                showVfsOptionsDialog = false
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showVfsOptionsDialog = false
            },
        )
    }
}

@Composable
private fun allowLockedAccessSummary(allowLockedAccess: Boolean) = if (allowLockedAccess) {
    stringResource(R.string.pref_edit_remote_allow_locked_access_desc_on)
} else {
    stringResource(R.string.pref_edit_remote_allow_locked_access_desc_off)
}

@Composable
private fun reportUsageSummary(state: RemoteState) = when (state.features?.about) {
    null -> stringResource(R.string.pref_edit_remote_report_usage_desc_loading)
    true -> stringResource(R.string.pref_edit_remote_report_usage_desc_supported)
    false -> stringResource(R.string.pref_edit_remote_report_usage_desc_unsupported)
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewEditRemoteScreen() {
    val remote = "test"
    val state = RemoteState(
        config = RcloneRpc.RemoteConfig(),
        features = null,
    )

    AppTheme {
        AppScreen(
            title = { Text(text = remote) },
            onBack = {},
        ) { params ->
            EditRemoteContent(
                remote = remote,
                state = state,
                existingRemotes = listOf(remote),
                requireAuth = false,
                onRemoteOpen = {},
                onRemoteConfigure = {},
                onRemoteRename = {},
                onRemoteDuplicate = {},
                onRemoteDelete = {},
                onAllowExternalAccessChange = {},
                onAllowLockedAccessChange = {},
                onDynamicShortcutChange = {},
                onThumbnailsChange = {},
                onReportUsageChange = {},
                onVfsOptionsChange = { _, _ -> },
                isVfsCacheDirty = { false },
                contentPadding = params.contentPadding,
            )
        }
    }
}
