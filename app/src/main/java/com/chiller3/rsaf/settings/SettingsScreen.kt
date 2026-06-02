/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.rsaf.BuildConfig
import com.chiller3.rsaf.Logcat
import com.chiller3.rsaf.Permissions
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.formattedString
import com.chiller3.rsaf.rclone.RcloneConfig
import com.chiller3.rsaf.rclone.RcloneProvider
import com.chiller3.rsaf.ui.AppScreen
import com.chiller3.rsaf.ui.BetterSegmentedShapes
import com.chiller3.rsaf.ui.Preference
import com.chiller3.rsaf.ui.PreferenceCategory
import com.chiller3.rsaf.ui.PreferenceColumn
import com.chiller3.rsaf.ui.SwitchPreference
import com.chiller3.rsaf.ui.betterSegmentedShapes
import com.chiller3.rsaf.ui.theme.AppTheme

@Composable
fun SettingsScreen(
    onLockNow: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val addFileExtension = remember(reloadPrefs) { prefs.addFileExtension }
    val pretendLocal = remember(reloadPrefs) { prefs.pretendLocal }
    val requireAuth = remember(reloadPrefs) { prefs.requireAuth }
    val inactivityTimeout = remember(reloadPrefs) { prefs.inactivityTimeout }
    val allowBackup = remember(reloadPrefs) { prefs.allowBackup }
    val isDebugMode = remember(reloadPrefs) { prefs.isDebugMode }
    val verboseRcloneLogs = remember(reloadPrefs) { prefs.verboseRcloneLogs }

    var reloadPerms by remember { mutableIntStateOf(0) }
    val inhibitBatteryOpt = remember(reloadPerms) { Permissions.isInhibitingBatteryOpt(context) }
    val notificationsGranted = remember(reloadPerms) {
        Permissions.have(context, Permissions.NOTIFICATION)
    }
    val localStorageAccess = remember(reloadPerms) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Permissions.have(context, Permissions.LEGACY_STORAGE)
        }
    }

    val remotes by viewModel.remotes.collectAsStateWithLifecycle()
    val importExportState by viewModel.importExportState.collectAsStateWithLifecycle()

    val requestInteractiveConfiguration = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val remote = it.data?.getStringExtra(InteractiveConfigurationActivity.EXTRA_REMOTE)!!
        val cancelled = it.resultCode != Activity.RESULT_OK

        viewModel.interactiveConfigurationCompleted(remote, cancelled)
    }
    val requestEditRemote = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.remoteEdited()
    }
    val requestPermissionActivity = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        reloadPerms++
    }
    val requestPermissionsRequired = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.all { it.value }) {
            reloadPerms++
        } else {
            requestPermissionActivity.launch(Permissions.getAppInfoIntent(context))
        }
    }
    val requestSafImportConfiguration = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.startImportExport(ImportExportMode.IMPORT, it)
        }
    }
    val requestSafExportConfiguration = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RcloneConfig.MIMETYPE),
    ) { uri ->
        uri?.let {
            viewModel.startImportExport(ImportExportMode.EXPORT, it)
        }
    }
    val requestSafSaveLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Logcat.MIMETYPE),
    ) { uri ->
        uri?.let {
            viewModel.saveLogs(it)
        }
    }

    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = stringResource(R.string.app_name)) },
    ) { params ->
        LaunchedEffect(Unit) {
            viewModel.alerts.collect { alerts ->
                val alert = alerts.firstOrNull() ?: return@collect
                val msg = when (alert) {
                    is SettingsAlert.ListRemotesFailed ->
                        resources.getString(R.string.alert_list_remotes_failure)
                    is SettingsAlert.RemoteAddSucceeded ->
                        resources.getString(R.string.alert_add_remote_success, alert.remote)
                    is SettingsAlert.RemoteAddPartiallySucceeded ->
                        resources.getString(R.string.alert_add_remote_partial, alert.remote)
                    SettingsAlert.ImportSucceeded ->
                        resources.getString(R.string.alert_import_success)
                    SettingsAlert.ExportSucceeded ->
                        resources.getString(R.string.alert_export_success)
                    is SettingsAlert.ImportFailed ->
                        resources.getString(R.string.alert_import_failure)
                    is SettingsAlert.ExportFailed ->
                        resources.getString(R.string.alert_export_failure)
                    SettingsAlert.ImportCancelled ->
                        resources.getString(R.string.alert_import_cancelled)
                    SettingsAlert.ExportCancelled ->
                        resources.getString(R.string.alert_export_cancelled)
                    is SettingsAlert.LogcatSucceeded ->
                        resources.getString(R.string.alert_logcat_success, alert.uri.formattedString)
                    is SettingsAlert.LogcatFailed ->
                        resources.getString(R.string.alert_logcat_failure, alert.uri.formattedString)
                    SettingsAlert.BrowserNotFound ->
                        resources.getString(R.string.alert_browser_not_found)
                }
                val details = when (alert) {
                    is SettingsAlert.ListRemotesFailed -> alert.error
                    is SettingsAlert.RemoteAddSucceeded -> null
                    is SettingsAlert.RemoteAddPartiallySucceeded -> null
                    SettingsAlert.ImportSucceeded -> null
                    SettingsAlert.ExportSucceeded -> null
                    is SettingsAlert.ImportFailed -> alert.error
                    is SettingsAlert.ExportFailed -> alert.error
                    SettingsAlert.ImportCancelled -> null
                    SettingsAlert.ExportCancelled -> null
                    is SettingsAlert.LogcatSucceeded -> null
                    is SettingsAlert.LogcatFailed -> alert.error
                    SettingsAlert.BrowserNotFound -> null
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

        SettingsContent(
            importExportState = importExportState,
            inhibitBatteryOpt = inhibitBatteryOpt,
            notificationsGranted = notificationsGranted,
            remotes = remotes,
            addFileExtension = addFileExtension,
            pretendLocal = pretendLocal,
            localStorageAccess = localStorageAccess,
            requireAuth = requireAuth,
            inactivityTimeout = inactivityTimeout,
            allowBackup = allowBackup,
            isDebugMode = isDebugMode,
            rcloneVersion = Rcbridge.rbVersion(),
            verboseRcloneLogs = verboseRcloneLogs,
            onInhibitBatteryOptGrant = {
                requestPermissionActivity.launch(Permissions.getInhibitBatteryOptIntent(context))
            },
            onNotificationsGrant = {
                requestPermissionsRequired.launch(Permissions.NOTIFICATION)
            },
            onRemoteAdd = { name ->
                requestInteractiveConfiguration.launch(
                    InteractiveConfigurationActivity.createIntent(context, name, true),
                )
            },
            onRemoteEdit = { name ->
                requestEditRemote.launch(EditRemoteActivity.createIntent(context, name))
            },
            onConfigurationImport = {
                // We intentionally do not filter for specific MIME types because document providers
                // are inconsistent in what MIME types they report for .conf files.
                requestSafImportConfiguration.launch(arrayOf("*/*"))
            },
            onConfigurationExport = {
                requestSafExportConfiguration.launch(RcloneConfig.FILENAME)
            },
            onAddFileExtensionChange = { enabled ->
                prefs.addFileExtension = enabled
                reloadPrefs++
            },
            onPretendLocalChange = { enabled ->
                prefs.pretendLocal = enabled
                reloadPrefs++
            },
            onLocalStorageAccessChange = { enabled ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        "package:${BuildConfig.APPLICATION_ID}".toUri(),
                    )

                    requestPermissionActivity.launch(intent)
                } else if (enabled) {
                    requestPermissionsRequired.launch(Permissions.LEGACY_STORAGE)
                } else {
                    requestPermissionActivity.launch(Permissions.getAppInfoIntent(context))
                }
            },
            onRequireAuthChange = { enabled ->
                prefs.requireAuth = enabled
                reloadPrefs++
            },
            onInactivityTimeoutChange = { timeout ->
                prefs.inactivityTimeout = timeout
                reloadPrefs++
            },
            onLockNow = onLockNow,
            onAllowBackupChange = { enabled ->
                prefs.allowBackup = enabled
                reloadPrefs++
            },
            onDebugModeChange = { enabled ->
                prefs.isDebugMode = enabled
                reloadPrefs++
            },
            onSourceRepoOpen = {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(SettingsAlert.BrowserNotFound)
                }
            },
            onVerboseRcloneLogsChange = { enabled ->
                prefs.verboseRcloneLogs = enabled
                reloadPrefs++
            },
            onSaveLogs = {
                requestSafSaveLogs.launch(Logcat.FILENAME_DEFAULT)
            },
            onAddInternalCacheRemote = {
                viewModel.addInternalCacheRemote()
            },
            isVfsCacheDirty = {
                viewModel.isAnyVfsCacheDirty
            },
            contentPadding = params.contentPadding,
        )
    }

    if (importExportState?.status == ImportExportState.Status.NEED_PASSWORD) {
        PasswordDialog(
            mode = importExportState!!.mode,
            onSelect = { password ->
                viewModel.setImportExportPassword(RcloneConfig.Password(password))
            },
            onDismiss = {
                viewModel.cancelPendingImportExport()
            },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.activityActions.collect {
            if (it.refreshRoots) {
                RcloneProvider.notifyRootsChanged(context)
            }
            viewModel.activityActionCompleted()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(
    importExportState: ImportExportState?,
    inhibitBatteryOpt: Boolean,
    notificationsGranted: Boolean,
    remotes: List<Remote>,
    addFileExtension: Boolean,
    pretendLocal: Boolean,
    localStorageAccess: Boolean,
    requireAuth: Boolean,
    inactivityTimeout: Int,
    allowBackup: Boolean,
    isDebugMode: Boolean,
    rcloneVersion: String,
    verboseRcloneLogs: Boolean,
    onInhibitBatteryOptGrant: () -> Unit,
    onNotificationsGrant: () -> Unit,
    onRemoteAdd: (String) -> Unit,
    onRemoteEdit: (String) -> Unit,
    onConfigurationImport: () -> Unit,
    onConfigurationExport: () -> Unit,
    onAddFileExtensionChange: (Boolean) -> Unit,
    onPretendLocalChange: (Boolean) -> Unit,
    onLocalStorageAccessChange: (Boolean) -> Unit,
    onRequireAuthChange: (Boolean) -> Unit,
    onInactivityTimeoutChange: (Int) -> Unit,
    onLockNow: () -> Unit,
    onAllowBackupChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onSourceRepoOpen: () -> Unit,
    onVerboseRcloneLogsChange: (Boolean) -> Unit,
    onSaveLogs: () -> Unit,
    onAddInternalCacheRemote: () -> Unit,
    isVfsCacheDirty: () -> Boolean,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showVfsWarningDialog by rememberSaveable { mutableStateOf<VfsCacheDeletionReason?>(null) }
    var showRemoteNameDialog by rememberSaveable { mutableStateOf<RemoteNameDialogAction?>(null) }
    var showInactivityTimeoutDialog by rememberSaveable { mutableStateOf(false) }

    PreferenceColumn(contentPadding = contentPadding) {
        if (!inhibitBatteryOpt || !notificationsGranted) {
            item(key = "permissions") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_permissions)) },
                    modifier = Modifier.animateItem(),
                )
            }

            if (!inhibitBatteryOpt) {
                item(key = "inhibit_battery_opt") {
                    Preference(
                        onClick = onInhibitBatteryOptGrant,
                        shapes = if (!notificationsGranted) {
                            BetterSegmentedShapes.top()
                        } else {
                            BetterSegmentedShapes.single()
                        },
                        title = { Text(text = stringResource(R.string.pref_inhibit_battery_opt_name)) },
                        summary = { Text(text = stringResource(R.string.pref_inhibit_battery_opt_desc)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (!notificationsGranted) {
                item(key = "missing_notifications") {
                    Preference(
                        onClick = onNotificationsGrant,
                        shapes = if (!inhibitBatteryOpt) {
                            BetterSegmentedShapes.bottom()
                        } else {
                            BetterSegmentedShapes.single()
                        },
                        title = { Text(text = stringResource(R.string.pref_missing_notifications_name)) },
                        summary = { Text(text = stringResource(R.string.pref_missing_notifications_desc)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        item(key = "remotes") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_remotes)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "add_remote") {
            Preference(
                onClick = { showRemoteNameDialog = RemoteNameDialogAction.Add },
                enabled = importExportState == null,
                shapes = if (remotes.isEmpty()) {
                    BetterSegmentedShapes.single()
                } else {
                    BetterSegmentedShapes.top()
                },
                title = { Text(text = stringResource(R.string.pref_add_remote_name)) },
                summary = { Text(text = stringResource(R.string.pref_add_remote_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        itemsIndexed(remotes, key = { _, remote -> remote.name }) { index, remote ->
            Preference(
                onClick = { onRemoteEdit(remote.name) },
                enabled = importExportState == null,
                shapes = betterSegmentedShapes(index = index + 1, count = remotes.size + 1),
                title = { Text(text = remote.name) },
                summary = { Text(text = remote.provider.description) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "configuration") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_configuration)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "import_configuration") {
            Preference(
                onClick = {
                    if (isVfsCacheDirty()) {
                        showVfsWarningDialog = VfsCacheDeletionReason.Import
                    } else {
                        onConfigurationImport()
                    }
                },
                enabled = importExportState == null,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_import_configuration_name)) },
                summary = { Text(text = stringResource(R.string.pref_import_configuration_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "export_configuration") {
            Preference(
                onClick = onConfigurationExport,
                enabled = importExportState == null,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_export_configuration_name)) },
                summary = { Text(text = stringResource(R.string.pref_export_configuration_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "behavior") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_behavior)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "add_file_extension") {
            SwitchPreference(
                checked = addFileExtension,
                onCheckedChange = onAddFileExtensionChange,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_add_file_extension_name)) },
                summary = { Text(text = stringResource(R.string.pref_add_file_extension_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "pretend_local") {
            SwitchPreference(
                checked = pretendLocal,
                onCheckedChange = onPretendLocalChange,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_pretend_local_name)) },
                summary = { Text(text = stringResource(R.string.pref_pretend_local_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "local_storage_access") {
            SwitchPreference(
                checked = localStorageAccess,
                onCheckedChange = onLocalStorageAccessChange,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_local_storage_access_name)) },
                summary = { Text(text = stringResource(R.string.pref_local_storage_access_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "app_lock") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_app_lock)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "require_auth") {
            SwitchPreference(
                checked = requireAuth,
                onCheckedChange = onRequireAuthChange,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_require_auth_name)) },
                summary = { Text(text = stringResource(R.string.pref_require_auth_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "inactivity_timeout") {
            Preference(
                onClick = { showInactivityTimeoutDialog = true },
                enabled = requireAuth,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_inactivity_timeout_name)) },
                summary = { Text(text = inactivityTimeoutSummary(inactivityTimeout)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "lock_now") {
            Preference(
                onClick = onLockNow,
                enabled = requireAuth,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_lock_now_name)) },
                summary = { Text(text = stringResource(R.string.pref_lock_now_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "advanced") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_advanced)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "allow_backup") {
            SwitchPreference(
                checked = allowBackup,
                onCheckedChange = onAllowBackupChange,
                shapes = BetterSegmentedShapes.single(),
                title = { Text(text = stringResource(R.string.pref_allow_backup_name)) },
                summary = { Text(text = stringResource(R.string.pref_allow_backup_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "about") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_about)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "version") {
            Preference(
                onClick = onSourceRepoOpen,
                onLongClick = { onDebugModeChange(!isDebugMode) },
                shapes = BetterSegmentedShapes.single(),
                title = { Text(text = stringResource(R.string.pref_version_name)) },
                summary = { Text(text = versionSummary(isDebugMode, rcloneVersion)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (isDebugMode) {
            item(key = "debug") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_debug)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "verbose_rclone_logs") {
                SwitchPreference(
                    checked = verboseRcloneLogs,
                    onCheckedChange = onVerboseRcloneLogsChange,
                    shapes = BetterSegmentedShapes.top(),
                    title = { Text(text = stringResource(R.string.pref_verbose_rclone_logs_name)) },
                    summary = { Text(text = stringResource(R.string.pref_verbose_rclone_logs_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "save_logs") {
                Preference(
                    onClick = onSaveLogs,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_save_logs_name)) },
                    summary = { Text(text = stringResource(R.string.pref_save_logs_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "add_internal_cache_remote") {
                Preference(
                    onClick = onAddInternalCacheRemote,
                    shapes = BetterSegmentedShapes.bottom(),
                    title = { Text(text = stringResource(R.string.pref_add_internal_cache_remote_name)) },
                    summary = { Text(text = stringResource(R.string.pref_add_internal_cache_remote_desc)) },
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
                    VfsCacheDeletionReason.Import -> onConfigurationImport()
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
            existingRemotes = remotes.map { it.name },
            onSelect = { name ->
                onRemoteAdd(name)
                @Suppress("AssignedValueIsNeverRead")
                showRemoteNameDialog = null
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showRemoteNameDialog = null
            },
        )
    }

    if (showInactivityTimeoutDialog) {
        InactivityTimeoutDialog(
            initialTimeout = inactivityTimeout,
            onSelect = { timeout ->
                onInactivityTimeoutChange(timeout)
                @Suppress("AssignedValueIsNeverRead")
                showInactivityTimeoutDialog = false
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showInactivityTimeoutDialog = false
            },
        )
    }
}

@Composable
private fun inactivityTimeoutSummary(timeout: Int) =
    pluralStringResource(R.plurals.pref_inactivity_timeout_desc, timeout, timeout)

@Composable
private fun versionSummary(isDebugMode: Boolean, rcloneVersion: String) = buildString {
    append(BuildConfig.VERSION_NAME)

    append(" (")
    append(BuildConfig.BUILD_TYPE)
    if (isDebugMode) {
        append("+debugmode")
    }
    append(")\nrclone ")

    append(rcloneVersion)
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
private fun PreviewSettingsScreen() {
    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.app_name)) },
        ) { params ->
            SettingsContent(
                importExportState = null,
                inhibitBatteryOpt = false,
                notificationsGranted = false,
                remotes = emptyList(),
                addFileExtension = true,
                pretendLocal = false,
                localStorageAccess = false,
                requireAuth = true,
                inactivityTimeout = 60,
                allowBackup = false,
                isDebugMode = true,
                rcloneVersion = "1.74.2-rsaf.0",
                verboseRcloneLogs = false,
                onInhibitBatteryOptGrant = {},
                onNotificationsGrant = {},
                onRemoteAdd = {},
                onRemoteEdit = {},
                onConfigurationImport = {},
                onConfigurationExport = {},
                onAddFileExtensionChange = {},
                onPretendLocalChange = {},
                onLocalStorageAccessChange = {},
                onRequireAuthChange = {},
                onInactivityTimeoutChange = {},
                onLockNow = {},
                onAllowBackupChange = {},
                onDebugModeChange = {},
                onSourceRepoOpen = {},
                onVerboseRcloneLogsChange = {},
                onSaveLogs = {},
                onAddInternalCacheRemote = {},
                isVfsCacheDirty = { false },
                contentPadding = params.contentPadding,
            )
        }
    }
}
