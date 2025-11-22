/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.clearFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.get
import androidx.preference.size
import com.chiller3.rsaf.AppLock
import com.chiller3.rsaf.BuildConfig
import com.chiller3.rsaf.Logcat
import com.chiller3.rsaf.Permissions
import com.chiller3.rsaf.PreferenceBaseFragment
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.dialog.InactivityTimeoutDialogFragment
import com.chiller3.rsaf.dialog.InteractiveConfigurationDialogFragment
import com.chiller3.rsaf.dialog.RemoteNameDialogAction
import com.chiller3.rsaf.dialog.RemoteNameDialogFragment
import com.chiller3.rsaf.dialog.TextInputDialogFragment
import com.chiller3.rsaf.dialog.VfsCacheDeletionDialogFragment
import com.chiller3.rsaf.extension.formattedString
import com.chiller3.rsaf.rclone.KeepAliveService
import com.chiller3.rsaf.rclone.RcloneConfig
import com.chiller3.rsaf.rclone.RcloneProvider
import com.chiller3.rsaf.view.LongClickablePreference
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceBaseFragment(), FragmentResultListener,
    Preference.OnPreferenceClickListener, LongClickablePreference.OnPreferenceLongClickListener,
    Preference.OnPreferenceChangeListener {
    companion object {
        private val TAG = SettingsFragment::class.java.simpleName

        private val TAG_ADD_REMOTE_NAME = "$TAG.add_remote_name"
        private val TAG_IMPORT_EXPORT_PASSWORD = "$TAG.import_export_password"

        private val TAG_IMPORT_CONFIRM = "$TAG.import_confirm"

        fun documentsUiIntent(remote: String): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                val uri = DocumentsContract.buildRootUri(
                    BuildConfig.DOCUMENTS_AUTHORITY, remote)
                setDataAndType(uri, DocumentsContract.Root.MIME_TYPE_ITEM)
            }
    }

    override val requestTag: String = TAG

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var categoryPermissions: PreferenceCategory
    private lateinit var categoryRemotes: PreferenceCategory
    private lateinit var categoryConfiguration: PreferenceCategory
    private lateinit var categoryDebug: PreferenceCategory
    private lateinit var prefInhibitBatteryOpt: Preference
    private lateinit var prefMissingNotifications: Preference
    private lateinit var prefAddRemote: Preference
    private lateinit var prefLocalStorageAccess: SwitchPreferenceCompat
    private lateinit var prefImportConfiguration: Preference
    private lateinit var prefExportConfiguration: Preference
    private lateinit var prefInactivityTimeout: Preference
    private lateinit var prefLockNow: Preference
    private lateinit var prefVersion: LongClickablePreference
    private lateinit var prefSaveLogs: Preference
    private lateinit var prefAddInternalCacheRemote: Preference

    private val requestEditRemote =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            it.data?.extras?.getString(EditRemoteActivity.RESULT_NEW_REMOTE)?.let { newRemote ->
                editRemote(newRemote)
            }
            viewModel.remoteEdited()
        }
    private val requestInhibitBatteryOpt =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }
    private val requestPermissionRequired =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.all { it.value }) {
                refreshPermissions()
            } else {
                startActivity(Permissions.getAppInfoIntent(requireContext()))
            }
        }
    private val requestSafImportConfiguration =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                viewModel.startImportExport(ImportExportMode.IMPORT, it)
            }
        }
    private val requestSafExportConfiguration =
        registerForActivityResult(ActivityResultContracts.CreateDocument(RcloneConfig.MIMETYPE)) { uri ->
            uri?.let {
                viewModel.startImportExport(ImportExportMode.EXPORT, it)
            }
        }
    private val requestSafSaveLogs =
        registerForActivityResult(ActivityResultContracts.CreateDocument(Logcat.MIMETYPE)) { uri ->
            uri?.let {
                viewModel.saveLogs(it)
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)

        val context = requireContext()

        KeepAliveService.startWithScanOnce(context)

        prefs = Preferences(context)

        categoryPermissions = findPreference(Preferences.CATEGORY_PERMISSIONS)!!
        categoryRemotes = findPreference(Preferences.CATEGORY_REMOTES)!!
        categoryConfiguration = findPreference(Preferences.CATEGORY_CONFIGURATION)!!
        categoryDebug = findPreference(Preferences.CATEGORY_DEBUG)!!

        prefInhibitBatteryOpt = findPreference(Preferences.PREF_INHIBIT_BATTERY_OPT)!!
        prefInhibitBatteryOpt.onPreferenceClickListener = this

        prefMissingNotifications = findPreference(Preferences.PREF_MISSING_NOTIFICATIONS)!!
        prefMissingNotifications.onPreferenceClickListener = this

        prefAddRemote = findPreference(Preferences.PREF_ADD_REMOTE)!!
        prefAddRemote.onPreferenceClickListener = this

        prefLocalStorageAccess = findPreference(Preferences.PREF_LOCAL_STORAGE_ACCESS)!!
        prefLocalStorageAccess.onPreferenceChangeListener = this

        prefImportConfiguration = findPreference(Preferences.PREF_IMPORT_CONFIGURATION)!!
        prefImportConfiguration.onPreferenceClickListener = this

        prefExportConfiguration = findPreference(Preferences.PREF_EXPORT_CONFIGURATION)!!
        prefExportConfiguration.onPreferenceClickListener = this

        prefInactivityTimeout = findPreference(Preferences.PREF_INACTIVITY_TIMEOUT)!!
        prefInactivityTimeout.onPreferenceClickListener = this

        prefLockNow = findPreference(Preferences.PREF_LOCK_NOW)!!
        prefLockNow.onPreferenceClickListener = this

        prefVersion = findPreference(Preferences.PREF_VERSION)!!
        prefVersion.onPreferenceClickListener = this
        prefVersion.onPreferenceLongClickListener = this

        prefSaveLogs = findPreference(Preferences.PREF_SAVE_LOGS)!!
        prefSaveLogs.onPreferenceClickListener = this

        prefAddInternalCacheRemote = findPreference(Preferences.PREF_ADD_INTERNAL_CACHE_REMOTE)!!
        prefAddInternalCacheRemote.onPreferenceClickListener = this

        // Call this once first to avoid UI jank from elements shifting. We call it again in
        // onResume() because allowing the permissions does not restart the activity.
        refreshPermissions()

        refreshInactivityTimeout()
        refreshVersion()
        refreshDebugPrefs()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.alerts.collect {
                    it.firstOrNull()?.let { alert ->
                        onAlert(alert)
                    }
                }
            }
        }

        lifecycleScope.launch {
            // We don't need the lifecycle to be STARTED. This way, only the (slow) initial load
            // will animate in the list of remotes. The items will show up instantaneously for
            // configuration changes.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.remotes.collect { remotes ->
                    for (i in (0 until categoryRemotes.size).reversed()) {
                        val p = categoryRemotes[i]

                        if (p.key.startsWith(Preferences.PREF_EDIT_REMOTE_PREFIX)) {
                            categoryRemotes.removePreference(p)
                        }
                    }

                    for (remote in remotes) {
                        // Silently ignore remote types that are no longer supported
                        if (remote.provider == null) {
                            continue
                        }

                        val p = Preference(context).apply {
                            key = Preferences.PREF_EDIT_REMOTE_PREFIX + remote.name
                            isPersistent = false
                            title = remote.name
                            summary = remote.provider.description
                            isIconSpaceReserved = false
                            onPreferenceClickListener = this@SettingsFragment
                        }
                        categoryRemotes.addPreference(p)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.importExportState.collect { state ->
                    setConfigRelatedPreferencesEnabled(state == null)

                    if (state == null) {
                        return@collect
                    }

                    if (state.status == ImportExportState.Status.NEED_PASSWORD &&
                        parentFragmentManager.findFragmentByTag(
                            TAG_IMPORT_EXPORT_PASSWORD) == null) {
                        val (title, message, hint) = when (state.mode) {
                            ImportExportMode.IMPORT -> Triple(
                                getString(R.string.dialog_import_password_title),
                                getString(R.string.dialog_import_password_message),
                                getString(R.string.dialog_import_password_hint),
                            )
                            ImportExportMode.EXPORT -> Triple(
                                getString(R.string.dialog_export_password_title),
                                getString(R.string.dialog_export_password_message),
                                getString(R.string.dialog_export_password_hint),
                            )
                        }

                        TextInputDialogFragment.newInstance(title, message, hint, true)
                            .show(parentFragmentManager.beginTransaction(),
                                TAG_IMPORT_EXPORT_PASSWORD)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activityActions.collect {
                    if (it.refreshRoots) {
                        Log.d(TAG, "Notifying system of new SAF roots")
                        RcloneProvider.notifyRootsChanged(requireContext().contentResolver)
                    }
                    viewModel.activityActionCompleted()
                }
            }
        }

        for (key in arrayOf(
            TAG_ADD_REMOTE_NAME,
            TAG_IMPORT_EXPORT_PASSWORD,
            TAG_IMPORT_CONFIRM,
            InteractiveConfigurationDialogFragment.TAG,
            InactivityTimeoutDialogFragment.TAG,
        )) {
            parentFragmentManager.setFragmentResultListener(key, this, this)
        }
    }

    override fun onResume() {
        super.onResume()

        refreshPermissions()
    }

    private fun refreshPermissions() {
        val context = requireContext()

        val allowedInhibitBatteryOpt = Permissions.isInhibitingBatteryOpt(context)
        prefInhibitBatteryOpt.isVisible = !allowedInhibitBatteryOpt

        val allowedNotifications = Permissions.have(context, Permissions.NOTIFICATION)
        prefMissingNotifications.isVisible = !allowedNotifications

        categoryPermissions.isVisible = !(allowedInhibitBatteryOpt && allowedNotifications)

        prefLocalStorageAccess.isChecked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Permissions.have(context, Permissions.LEGACY_STORAGE)
        }
    }

    private fun refreshInactivityTimeout() {
        prefInactivityTimeout.summary = requireContext().resources.getQuantityString(
            R.plurals.pref_inactivity_timeout_desc,
            prefs.inactivityTimeout,
            prefs.inactivityTimeout,
        )
    }

    private fun refreshVersion() {
        prefVersion.summary = buildString {
            append(BuildConfig.VERSION_NAME)

            append(" (")
            append(BuildConfig.BUILD_TYPE)
            if (prefs.isDebugMode) {
                append("+debugmode")
            }
            append(")\nrclone ")

            append(Rcbridge.rbVersion())
        }
    }

    private fun refreshDebugPrefs() {
        categoryDebug.isVisible = prefs.isDebugMode
    }

    private fun setConfigRelatedPreferencesEnabled(enabled: Boolean) {
        categoryRemotes.isEnabled = enabled
        categoryConfiguration.isEnabled = enabled
    }

    override fun onFragmentResult(requestKey: String, bundle: Bundle) {
        clearFragmentResult(requestKey)

        when (requestKey) {
            TAG_ADD_REMOTE_NAME -> {
                if (bundle.getBoolean(RemoteNameDialogFragment.RESULT_SUCCESS)) {
                    val remote = bundle.getString(RemoteNameDialogFragment.RESULT_INPUT)!!

                    InteractiveConfigurationDialogFragment.newInstance(remote, true)
                        .show(parentFragmentManager.beginTransaction(),
                            InteractiveConfigurationDialogFragment.TAG)
                }
            }
            TAG_IMPORT_EXPORT_PASSWORD -> {
                if (bundle.getBoolean(TextInputDialogFragment.RESULT_SUCCESS)) {
                    val password = bundle.getString(TextInputDialogFragment.RESULT_INPUT)!!
                    viewModel.setImportExportPassword(password)
                } else {
                    viewModel.cancelPendingImportExport()
                }
            }
            TAG_IMPORT_CONFIRM -> {
                if (bundle.getBoolean(VfsCacheDeletionDialogFragment.RESULT_SUCCESS)) {
                    confirmImport(true)
                }
            }
            InteractiveConfigurationDialogFragment.TAG -> {
                viewModel.interactiveConfigurationCompleted(
                    bundle.getString(InteractiveConfigurationDialogFragment.RESULT_REMOTE)!!,
                    bundle.getBoolean(InteractiveConfigurationDialogFragment.RESULT_CANCELLED),
                )
            }
            InactivityTimeoutDialogFragment.TAG -> {
                refreshInactivityTimeout()
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when {
            preference === prefInhibitBatteryOpt -> {
                requestInhibitBatteryOpt.launch(
                    Permissions.getInhibitBatteryOptIntent(requireContext()))
                return true
            }
            preference === prefMissingNotifications -> {
                requestPermissionRequired.launch(Permissions.NOTIFICATION)
                return true
            }
            preference === prefAddRemote -> {
                RemoteNameDialogFragment.newInstance(
                    requireContext(),
                    RemoteNameDialogAction.Add,
                    viewModel.remotes.value.map { it.name }.toTypedArray(),
                ).show(parentFragmentManager.beginTransaction(), TAG_ADD_REMOTE_NAME)
                return true
            }
            preference.key.startsWith(Preferences.PREF_EDIT_REMOTE_PREFIX) -> {
                val remote = preference.key.substring(Preferences.PREF_EDIT_REMOTE_PREFIX.length)
                editRemote(remote)
                return true
            }
            preference === prefImportConfiguration -> {
                confirmImport(false)
                return true
            }
            preference === prefExportConfiguration -> {
                requestSafExportConfiguration.launch(RcloneConfig.FILENAME)
                return true
            }
            preference === prefInactivityTimeout -> {
                InactivityTimeoutDialogFragment().show(
                    parentFragmentManager.beginTransaction(),
                    InactivityTimeoutDialogFragment.TAG,
                )
                return true
            }
            preference === prefLockNow -> {
                AppLock.onLock()
                requireActivity().finishAndRemoveTask()
                return true
            }
            preference === prefVersion -> {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            preference === prefSaveLogs -> {
                requestSafSaveLogs.launch(Logcat.FILENAME_DEFAULT)
                return true
            }
            preference === prefAddInternalCacheRemote -> {
                viewModel.addInternalCacheRemote()
                return true
            }
        }

        return false
    }

    override fun onPreferenceLongClick(preference: Preference): Boolean {
        when (preference) {
            prefVersion -> {
                prefs.isDebugMode = !prefs.isDebugMode
                refreshVersion()
                refreshDebugPrefs()
                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference) {
            prefLocalStorageAccess -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        "package:${BuildConfig.APPLICATION_ID}".toUri(),
                    )

                    startActivity(intent)
                } else if (newValue == true) {
                    requestPermissionRequired.launch(Permissions.LEGACY_STORAGE)
                } else {
                    startActivity(Permissions.getAppInfoIntent(requireContext()))
                }

                // We rely on onPause() to adjust the switch state when the user comes back from the
                // settings app.
                return false
            }
        }

        return false
    }

    private fun onAlert(alert: SettingsAlert) {
        val msg = when (alert) {
            is SettingsAlert.ListRemotesFailed ->
                getString(R.string.alert_list_remotes_failure, alert.error)
            is SettingsAlert.RemoteAddSucceeded ->
                getString(R.string.alert_add_remote_success, alert.remote)
            is SettingsAlert.RemoteAddPartiallySucceeded ->
                getString(R.string.alert_add_remote_partial, alert.remote)
            SettingsAlert.ImportSucceeded -> getString(R.string.alert_import_success)
            SettingsAlert.ExportSucceeded -> getString(R.string.alert_export_success)
            is SettingsAlert.ImportFailed -> getString(R.string.alert_import_failure, alert.error)
            is SettingsAlert.ExportFailed -> getString(R.string.alert_export_failure, alert.error)
            SettingsAlert.ImportCancelled -> getString(R.string.alert_import_cancelled)
            SettingsAlert.ExportCancelled -> getString(R.string.alert_export_cancelled)
            is SettingsAlert.LogcatSucceeded ->
                getString(R.string.alert_logcat_success, alert.uri.formattedString)
            is SettingsAlert.LogcatFailed ->
                getString(R.string.alert_logcat_failure, alert.uri.formattedString, alert.error)
        }

        Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG)
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    viewModel.acknowledgeFirstAlert()
                }
            })
            .show()
    }

    private fun confirmImport(force: Boolean) {
        if (!force && viewModel.isAnyVfsCacheDirty) {
            VfsCacheDeletionDialogFragment.newInstance(
                getString(R.string.dialog_import_password_title),
            ).show(parentFragmentManager.beginTransaction(), TAG_IMPORT_CONFIRM)
        } else {
            // We intentionally do not filter for specific MIME types because document providers
            // are inconsistent in what MIME types they report for .conf files.
            requestSafImportConfiguration.launch(arrayOf("*/*"))
        }
    }

    private fun editRemote(remote: String) {
        requestEditRemote.launch(EditRemoteActivity.createIntent(requireContext(), remote))
    }
}
