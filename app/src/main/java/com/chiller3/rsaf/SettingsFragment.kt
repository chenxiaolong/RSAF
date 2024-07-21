package com.chiller3.rsaf

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.clearFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.get
import androidx.preference.size
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat(), FragmentResultListener,
    Preference.OnPreferenceClickListener, LongClickablePreference.OnPreferenceLongClickListener,
    Preference.OnPreferenceChangeListener {
    companion object {
        private val TAG = SettingsFragment::class.java.simpleName

        private val TAG_ADD_REMOTE_NAME = "$TAG.add_remote_name"
        private val TAG_EDIT_REMOTE = "$TAG.edit_remote"
        private val TAG_RENAME_REMOTE = "$TAG.rename_remote"
        private val TAG_DUPLICATE_REMOTE = "$TAG.duplicate_remote"
        private val TAG_IMPORT_EXPORT_PASSWORD = "$TAG.import_export_password"

        private const val ARG_OLD_REMOTE_NAME = "old_remote_name"

        private const val STATE_AUTHENTICATED = "authenticated"
        private const val STATE_LAST_PAUSE = "last_pause"

        private const val INACTIVE_TIMEOUT_NS = 60_000_000_000L

        private fun documentsUiIntent(remote: String): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                val uri = DocumentsContract.buildRootUri(
                    BuildConfig.DOCUMENTS_AUTHORITY, remote)
                setDataAndType(uri, DocumentsContract.Root.MIME_TYPE_ITEM)
            }
    }

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var categoryRemotes: PreferenceCategory
    private lateinit var categoryConfiguration: PreferenceCategory
    private lateinit var categoryDebug: PreferenceCategory
    private lateinit var prefAddRemote: Preference
    private lateinit var prefLocalStorageAccess: SwitchPreferenceCompat
    private lateinit var prefImportConfiguration: Preference
    private lateinit var prefExportConfiguration: Preference
    private lateinit var prefVersion: LongClickablePreference
    private lateinit var prefSaveLogs: Preference
    private lateinit var bioPrompt: BiometricPrompt
    private var bioAuthenticated = false
    private var lastPause = 0L

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

        if (savedInstanceState != null) {
            bioAuthenticated = savedInstanceState.getBoolean(STATE_AUTHENTICATED)
            lastPause = savedInstanceState.getLong(STATE_LAST_PAUSE)
        }

        val activity = requireActivity()

        prefs = Preferences(activity)

        categoryRemotes = findPreference(Preferences.CATEGORY_REMOTES)!!
        categoryConfiguration = findPreference(Preferences.CATEGORY_CONFIGURATION)!!
        categoryDebug = findPreference(Preferences.CATEGORY_DEBUG)!!

        prefAddRemote = findPreference(Preferences.PREF_ADD_REMOTE)!!
        prefAddRemote.onPreferenceClickListener = this

        prefLocalStorageAccess = findPreference(Preferences.PREF_LOCAL_STORAGE_ACCESS)!!
        prefLocalStorageAccess.onPreferenceChangeListener = this

        prefImportConfiguration = findPreference(Preferences.PREF_IMPORT_CONFIGURATION)!!
        prefImportConfiguration.onPreferenceClickListener = this

        prefExportConfiguration = findPreference(Preferences.PREF_EXPORT_CONFIGURATION)!!
        prefExportConfiguration.onPreferenceClickListener = this

        prefVersion = findPreference(Preferences.PREF_VERSION)!!
        prefVersion.onPreferenceClickListener = this
        prefVersion.onPreferenceLongClickListener = this

        prefSaveLogs = findPreference(Preferences.PREF_SAVE_LOGS)!!
        prefSaveLogs.onPreferenceClickListener = this

        refreshVersion()
        refreshDebugPrefs()

        bioPrompt = BiometricPrompt(
            this,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(
                        activity,
                        getString(R.string.biometric_error, errString),
                        Toast.LENGTH_LONG,
                    ).show()
                    activity.finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    bioAuthenticated = true
                    refreshGlobalVisibility()
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(
                        activity,
                        R.string.biometric_failure,
                        Toast.LENGTH_LONG,
                    ).show()
                    activity.finish()
                }
            },
        )

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

                        val p = Preference(activity).apply {
                            key = Preferences.PREF_EDIT_REMOTE_PREFIX + remote.name
                            isPersistent = false
                            title = remote.name
                            summary = remote.provider.description
                            isIconSpaceReserved = false
                            onPreferenceClickListener = this@SettingsFragment
                        }
                        categoryRemotes.addPreference(p)
                    }

                    updateShortcuts(remotes)
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

        for (key in arrayOf(
            TAG_ADD_REMOTE_NAME,
            TAG_EDIT_REMOTE,
            TAG_RENAME_REMOTE,
            TAG_DUPLICATE_REMOTE,
            TAG_IMPORT_EXPORT_PASSWORD,
            InteractiveConfigurationDialogFragment.TAG,
        )) {
            parentFragmentManager.setFragmentResultListener(key, this, this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(STATE_AUTHENTICATED, bioAuthenticated)
        outState.putLong(STATE_LAST_PAUSE, lastPause)
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            prefLocalStorageAccess.isChecked = Environment.isExternalStorageManager()
        } else {
            prefLocalStorageAccess.isVisible = false
        }

        if (bioAuthenticated && (System.nanoTime() - lastPause) >= INACTIVE_TIMEOUT_NS) {
            bioAuthenticated = false
        }

        if (!bioAuthenticated) {
            if (!prefs.requireAuth) {
                bioAuthenticated = true
            } else {
                startBiometricAuth()
            }
        }

        refreshGlobalVisibility()
    }

    override fun onPause() {
        super.onPause()

        lastPause = System.nanoTime()
    }

    private fun startBiometricAuth() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .setTitle(getString(R.string.biometric_title))
            .build()

        bioPrompt.authenticate(promptInfo)
    }

    private fun refreshGlobalVisibility() {
        view?.visibility = if (bioAuthenticated) {
            View.VISIBLE
        } else {
            // Using View.GONE causes noticeable scrolling jank due to relayout.
            View.INVISIBLE
        }
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
            TAG_EDIT_REMOTE -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getSerializable(EditRemoteDialogFragment.RESULT_ACTION,
                        EditRemoteDialogFragment.Action::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    bundle.getSerializable(EditRemoteDialogFragment.RESULT_ACTION)
                            as EditRemoteDialogFragment.Action?
                }
                val remote = bundle.getString(EditRemoteDialogFragment.RESULT_REMOTE)!!

                when (action) {
                    EditRemoteDialogFragment.Action.OPEN -> {
                        startActivity(documentsUiIntent(remote))
                    }
                    EditRemoteDialogFragment.Action.BLOCK -> {
                        viewModel.blockRemote(remote, true)
                    }
                    EditRemoteDialogFragment.Action.UNBLOCK -> {
                        viewModel.blockRemote(remote, false)
                    }
                    EditRemoteDialogFragment.Action.CONFIGURE -> {
                        InteractiveConfigurationDialogFragment.newInstance(remote, false)
                            .show(parentFragmentManager.beginTransaction(),
                                InteractiveConfigurationDialogFragment.TAG)
                    }
                    EditRemoteDialogFragment.Action.RENAME -> {
                        showRemoteNameDialog(
                            TAG_RENAME_REMOTE,
                            getString(R.string.dialog_rename_remote_title, remote),
                        ) {
                            it.putString(ARG_OLD_REMOTE_NAME, remote)
                        }
                    }
                    EditRemoteDialogFragment.Action.DUPLICATE -> {
                        showRemoteNameDialog(
                            TAG_DUPLICATE_REMOTE,
                            getString(R.string.dialog_duplicate_remote_title, remote),
                        ) {
                            it.putString(ARG_OLD_REMOTE_NAME, remote)
                        }
                    }
                    EditRemoteDialogFragment.Action.DELETE -> {
                        viewModel.deleteRemote(remote)
                    }
                    null -> {
                        // Cancelled
                    }
                }
            }
            TAG_RENAME_REMOTE -> {
                if (bundle.getBoolean(RemoteNameDialogFragment.RESULT_SUCCESS)) {
                    val newRemote = bundle.getString(RemoteNameDialogFragment.RESULT_INPUT)!!
                    val oldRemote = bundle.getBundle(RemoteNameDialogFragment.RESULT_ARGS)!!
                        .getString(ARG_OLD_REMOTE_NAME)!!

                    viewModel.renameRemote(oldRemote, newRemote)
                }
            }
            TAG_DUPLICATE_REMOTE -> {
                if (bundle.getBoolean(RemoteNameDialogFragment.RESULT_SUCCESS)) {
                    val newRemote = bundle.getString(RemoteNameDialogFragment.RESULT_INPUT)!!
                    val oldRemote = bundle.getBundle(RemoteNameDialogFragment.RESULT_ARGS)!!
                        .getString(ARG_OLD_REMOTE_NAME)!!

                    viewModel.duplicateRemote(oldRemote, newRemote)
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
            InteractiveConfigurationDialogFragment.TAG -> {
                viewModel.interactiveConfigurationCompleted(
                    bundle.getString(InteractiveConfigurationDialogFragment.RESULT_REMOTE)!!,
                    bundle.getBoolean(InteractiveConfigurationDialogFragment.RESULT_NEW),
                    bundle.getBoolean(InteractiveConfigurationDialogFragment.RESULT_CANCELLED),
                )
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when {
            preference === prefAddRemote -> {
                showRemoteNameDialog(TAG_ADD_REMOTE_NAME,
                    getString(R.string.dialog_add_remote_title))
                return true
            }
            preference.key.startsWith(Preferences.PREF_EDIT_REMOTE_PREFIX) -> {
                val remote = preference.key.substring(Preferences.PREF_EDIT_REMOTE_PREFIX.length)
                val isBlocked = viewModel.remotes.value.find { it.name == remote }
                    ?.config?.get(RcloneRpc.CUSTOM_OPT_BLOCKED) == "true"

                EditRemoteDialogFragment.newInstance(remote, isBlocked)
                    .show(parentFragmentManager.beginTransaction(), TAG_EDIT_REMOTE)
                return true
            }
            preference === prefImportConfiguration -> {
                // Android does not recognize .conf suffix as a text file
                requestSafImportConfiguration.launch(
                    arrayOf(RcloneConfig.MIMETYPE, "application/octet-stream"))
                return true
            }
            preference === prefExportConfiguration -> {
                requestSafExportConfiguration.launch(RcloneConfig.FILENAME)
                return true
            }
            preference === prefVersion -> {
                val uri = Uri.parse(BuildConfig.PROJECT_URL_AT_COMMIT)
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            preference === prefSaveLogs -> {
                requestSafSaveLogs.launch(Logcat.FILENAME_DEFAULT)
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
                        Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
                    )

                    startActivity(intent)
                }

                // We rely on onPause() to adjust the switch state when the user comes back from the
                // settings app.
                return false
            }
        }

        return false
    }

    private fun notifyRootsChanged() {
        RcloneProvider.notifyRootsChanged(requireContext().contentResolver)
    }

    private fun onAlert(alert: Alert) {
        val msg = when (alert) {
            is ListRemotesFailed -> getString(R.string.alert_list_remotes_failure, alert.error)
            is RemoteAddSucceeded -> getString(R.string.alert_add_remote_success, alert.remote)
            is RemoteAddPartiallySucceeded -> getString(R.string.alert_add_remote_partial,
                alert.remote)
            is RemoteEditSucceeded -> getString(R.string.alert_edit_remote_success, alert.remote)
            is RemoteDeleteSucceeded -> getString(R.string.alert_delete_remote_success,
                alert.remote)
            is RemoteDeleteFailed -> getString(R.string.alert_delete_remote_failure,
                alert.remote, alert.error)
            is RemoteRenameSucceeded -> getString(R.string.alert_rename_remote_success,
                alert.oldRemote, alert.newRemote)
            is RemoteRenameFailed -> getString(R.string.alert_rename_remote_failure,
                alert.oldRemote, alert.newRemote, alert.error)
            is RemoteDuplicateSucceeded -> getString(R.string.alert_duplicate_remote_success,
                alert.oldRemote, alert.newRemote)
            is RemoteDuplicateFailed -> getString(R.string.alert_duplicate_remote_failure,
                alert.oldRemote, alert.newRemote, alert.error)
            is RemoteBlockUnblockSucceeded -> if (alert.blocked) {
                getString(R.string.alert_block_remote_success, alert.remote)
            } else {
                getString(R.string.alert_unblock_remote_success, alert.remote)
            }
            is RemoteBlockUnblockFailed -> if (alert.block) {
                getString(R.string.alert_block_remote_failure, alert.remote, alert.error)
            } else {
                getString(R.string.alert_unblock_remote_failure, alert.remote, alert.error)
            }
            ImportSucceeded -> getString(R.string.alert_import_success)
            ExportSucceeded -> getString(R.string.alert_export_success)
            is ImportFailed -> getString(R.string.alert_import_failure, alert.error)
            is ExportFailed -> getString(R.string.alert_export_failure, alert.error)
            ImportCancelled -> getString(R.string.alert_import_cancelled)
            ExportCancelled -> getString(R.string.alert_export_cancelled)
            is LogcatSucceeded -> getString(R.string.alert_logcat_success,
                alert.uri.formattedString)
            is LogcatFailed -> getString(R.string.alert_logcat_failure,
                alert.uri.formattedString, alert.error)
        }

        Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG)
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    viewModel.acknowledgeFirstAlert()
                }
            })
            .show()

        if (alert.requireNotifyRootsChanged) {
            notifyRootsChanged()
        }
    }

    private fun showRemoteNameDialog(tag: String, title: String,
                                     argModifier: ((Bundle) -> Unit)? = null) {
        RemoteNameDialogFragment.newInstance(
            title,
            getString(R.string.dialog_remote_name_message),
            getString(R.string.dialog_remote_name_hint),
            viewModel.remotes.value.map { it.name }.toTypedArray(),
        ).apply {
            if (argModifier != null) {
                argModifier(requireArguments())
            }
        }.show(parentFragmentManager.beginTransaction(), tag)
    }

    private fun updateShortcuts(remotes: List<Remote>) {
        val context = requireContext()

        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        var rank = 0

        for (remote in remotes) {
            val isBlocked = remote.config[RcloneRpc.CUSTOM_OPT_BLOCKED] == "true"
            if (isBlocked) {
                continue
            }

            val shortcut = ShortcutInfoCompat.Builder(context, remote.name)
                .setShortLabel(remote.name)
                .setIcon(icon)
                .setIntent(documentsUiIntent(remote.name))
                .setRank(rank)
                .build()

            shortcuts.add(shortcut)
            rank += 1
        }

        if (!ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) {
            Log.w(TAG, "Failed to update dynamic shortcuts")
        }
    }
}
