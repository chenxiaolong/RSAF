/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.clearFragmentResult
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.chiller3.rsaf.PreferenceBaseFragment
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.dialog.InteractiveConfigurationDialogFragment
import com.chiller3.rsaf.dialog.RemoteNameDialogAction
import com.chiller3.rsaf.dialog.RemoteNameDialogFragment
import com.chiller3.rsaf.dialog.VfsCacheDeletionDialogFragment
import com.chiller3.rsaf.rclone.RcloneProvider
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.settings.SettingsFragment.Companion.documentsUiIntent
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class EditRemoteFragment : PreferenceBaseFragment(), FragmentResultListener,
    Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
    companion object {
        private val TAG = EditRemoteFragment::class.java.simpleName

        internal const val ARG_REMOTE = "remote"

        private val TAG_RENAME_REMOTE = "$TAG.rename_remote"
        private val TAG_DUPLICATE_REMOTE = "$TAG.duplicate_remote"

        private val TAG_RENAME_REMOTE_CONFIRM = "$TAG.rename_remote_confirm"
        private val TAG_DELETE_REMOTE_CONFIRM = "$TAG.delete_remote_confirm"
    }

    override val requestTag: String = TAG

    private val viewModel: EditRemoteViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var prefOpenRemote: Preference
    private lateinit var prefConfigureRemote: Preference
    private lateinit var prefRenameRemote: Preference
    private lateinit var prefDuplicateRemote: Preference
    private lateinit var prefDeleteRemote: Preference
    private lateinit var prefAllowExternalAccess: SwitchPreferenceCompat
    private lateinit var prefAllowLockedAccess: SwitchPreferenceCompat
    private lateinit var prefDynamicShortcut: SwitchPreferenceCompat
    private lateinit var prefThumbnails: SwitchPreferenceCompat
    private lateinit var prefVfsCaching: SwitchPreferenceCompat
    private lateinit var prefReportUsage: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_edit_remote, rootKey)

        prefs = Preferences(requireContext())

        prefOpenRemote = findPreference(Preferences.PREF_OPEN_REMOTE)!!
        prefOpenRemote.onPreferenceClickListener = this

        prefConfigureRemote = findPreference(Preferences.PREF_CONFIGURE_REMOTE)!!
        prefConfigureRemote.onPreferenceClickListener = this

        prefRenameRemote = findPreference(Preferences.PREF_RENAME_REMOTE)!!
        prefRenameRemote.onPreferenceClickListener = this

        prefDuplicateRemote = findPreference(Preferences.PREF_DUPLICATE_REMOTE)!!
        prefDuplicateRemote.onPreferenceClickListener = this

        prefDeleteRemote = findPreference(Preferences.PREF_DELETE_REMOTE)!!
        prefDeleteRemote.onPreferenceClickListener = this

        prefAllowExternalAccess = findPreference(Preferences.PREF_ALLOW_EXTERNAL_ACCESS)!!
        prefAllowExternalAccess.onPreferenceChangeListener = this

        prefAllowLockedAccess = findPreference(Preferences.PREF_ALLOW_LOCKED_ACCESS)!!
        prefAllowLockedAccess.onPreferenceChangeListener = this

        prefDynamicShortcut = findPreference(Preferences.PREF_DYNAMIC_SHORTCUT)!!
        prefDynamicShortcut.onPreferenceChangeListener = this

        prefThumbnails = findPreference(Preferences.PREF_THUMBNAILS)!!
        prefThumbnails.onPreferenceChangeListener = this

        prefVfsCaching = findPreference(Preferences.PREF_VFS_CACHING)!!
        prefVfsCaching.onPreferenceChangeListener = this

        prefReportUsage = findPreference(Preferences.PREF_REPORT_USAGE)!!
        prefReportUsage.onPreferenceChangeListener = this

        viewModel.remote = requireArguments().getString(ARG_REMOTE)!!

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.remoteConfigs.collect {
                    Log.d(TAG, "Updating dynamic shortcuts")
                    updateShortcuts(it)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.remoteState.collect { state ->
                    prefOpenRemote.isEnabled = state.allowExternalAccessOrDefault == true

                    prefAllowExternalAccess.isEnabled = state.allowExternalAccessOrDefault != null
                    state.allowExternalAccessOrDefault?.let {
                        prefAllowExternalAccess.isChecked = it
                    }

                    prefAllowLockedAccess.isEnabled = state.allowExternalAccessOrDefault == true
                            && prefs.requireAuth
                    state.allowLockedAccessOrDefault?.let {
                        prefAllowLockedAccess.isChecked = it
                    }

                    prefDynamicShortcut.isEnabled = state.allowExternalAccessOrDefault == true
                    state.config?.dynamicShortcutOrDefault?.let {
                        prefDynamicShortcut.isChecked = it
                    }

                    prefThumbnails.isEnabled = state.allowExternalAccessOrDefault == true
                    state.config?.thumbnailsOrDefault?.let {
                        prefThumbnails.isChecked = it
                    }

                    prefVfsCaching.isEnabled = state.allowExternalAccessOrDefault == true
                            && state.features?.putStream == true
                    state.config?.vfsCachingOrDefault?.let {
                        prefVfsCaching.isChecked = it
                    }
                    prefVfsCaching.summary = when (state.features?.putStream) {
                        null -> getString(R.string.pref_edit_remote_vfs_caching_desc_loading)
                        true -> getString(R.string.pref_edit_remote_vfs_caching_desc_optional)
                        false -> getString(R.string.pref_edit_remote_vfs_caching_desc_required)
                    }

                    prefReportUsage.isEnabled = state.allowExternalAccessOrDefault == true
                            && state.features?.about == true

                    state.config?.reportUsageOrDefault?.let {
                        prefReportUsage.isChecked = it
                    }
                    prefReportUsage.summary = when (state.features?.about) {
                        null -> getString(R.string.pref_edit_remote_report_usage_desc_loading)
                        true -> getString(R.string.pref_edit_remote_report_usage_desc_supported)
                        false -> getString(R.string.pref_edit_remote_report_usage_desc_unsupported)
                    }
                }
            }
        }

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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activityActions.collect {
                    if (it.refreshRoots) {
                        Log.d(TAG, "Notifying system of new SAF roots")
                        RcloneProvider.notifyRootsChanged(requireContext().contentResolver)
                    }
                    it.editNewRemote?.let { newRemote ->
                        Log.d(TAG, "Editing new remote: $newRemote")
                        setFragmentResult(requestTag, bundleOf(
                            EditRemoteActivity.RESULT_NEW_REMOTE to newRemote,
                        ))
                    }
                    if (it.finish) {
                        Log.d(TAG, "Finishing edit remote activity for: ${viewModel.remote}")
                        requireActivity().finish()
                    }
                    viewModel.activityActionCompleted()
                }
            }
        }

        for (key in arrayOf(
            TAG_RENAME_REMOTE,
            TAG_DUPLICATE_REMOTE,
            TAG_RENAME_REMOTE_CONFIRM,
            TAG_DELETE_REMOTE_CONFIRM,
            InteractiveConfigurationDialogFragment.TAG,
        )) {
            parentFragmentManager.setFragmentResultListener(key, this, this)
        }
    }

    override fun onFragmentResult(requestKey: String, bundle: Bundle) {
        clearFragmentResult(requestKey)

        when (requestKey) {
            TAG_RENAME_REMOTE -> {
                if (bundle.getBoolean(RemoteNameDialogFragment.RESULT_SUCCESS)) {
                    val newRemote = bundle.getString(RemoteNameDialogFragment.RESULT_INPUT)!!

                    viewModel.renameRemote(newRemote)
                }
            }
            TAG_DUPLICATE_REMOTE -> {
                if (bundle.getBoolean(RemoteNameDialogFragment.RESULT_SUCCESS)) {
                    val newRemote = bundle.getString(RemoteNameDialogFragment.RESULT_INPUT)!!

                    viewModel.duplicateRemote(newRemote)
                }
            }
            TAG_RENAME_REMOTE_CONFIRM -> {
                if (bundle.getBoolean(VfsCacheDeletionDialogFragment.RESULT_SUCCESS)) {
                    confirmRenameDialog(true)
                }
            }
            TAG_DELETE_REMOTE_CONFIRM -> {
                if (bundle.getBoolean(VfsCacheDeletionDialogFragment.RESULT_SUCCESS)) {
                    confirmDelete(true)
                }
            }
            InteractiveConfigurationDialogFragment.TAG -> {
                viewModel.interactiveConfigurationCompleted(
                    bundle.getString(InteractiveConfigurationDialogFragment.RESULT_REMOTE)!!,
                )
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            prefOpenRemote -> {
                startActivity(documentsUiIntent(viewModel.remote))
                return true
            }
            prefConfigureRemote -> {
                InteractiveConfigurationDialogFragment.newInstance(viewModel.remote, false)
                    .show(parentFragmentManager.beginTransaction(),
                        InteractiveConfigurationDialogFragment.TAG)
                return true
            }
            prefRenameRemote -> {
                confirmRenameDialog(false)
                return true
            }
            prefDuplicateRemote -> {
                RemoteNameDialogFragment.newInstance(
                    requireContext(),
                    RemoteNameDialogAction.Duplicate(viewModel.remote),
                    viewModel.remoteConfigs.value.keys.toTypedArray(),
                ).show(parentFragmentManager.beginTransaction(), TAG_DUPLICATE_REMOTE)
                return true
            }
            prefDeleteRemote -> {
                confirmDelete(false)
                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        // These all return false because the state is updated when the change actually happens.

        when (preference) {
            prefAllowExternalAccess -> {
                viewModel.setExternalAccess(newValue as Boolean)
            }
            prefAllowLockedAccess -> {
                viewModel.setLockedAccess(newValue as Boolean)
            }
            prefDynamicShortcut -> {
                viewModel.setDynamicShortcut(newValue as Boolean)
            }
            prefThumbnails -> {
                viewModel.setThumbnails(newValue as Boolean)
            }
            prefVfsCaching -> {
                viewModel.setVfsCaching(newValue as Boolean)
            }
            prefReportUsage -> {
                viewModel.setReportUsage(newValue as Boolean)
            }
        }

        return false
    }

    private fun onAlert(alert: EditRemoteAlert) {
        val msg = when (alert) {
            is EditRemoteAlert.ListRemotesFailed ->
                getString(R.string.alert_list_remotes_failure, alert.error)
            is EditRemoteAlert.RemoteEditSucceeded ->
                getString(R.string.alert_edit_remote_success, alert.remote)
            is EditRemoteAlert.RemoteDeleteFailed ->
                getString(R.string.alert_delete_remote_failure, alert.remote, alert.error)
            is EditRemoteAlert.RemoteRenameFailed ->
                getString(R.string.alert_rename_remote_failure, alert.oldRemote, alert.newRemote,
                    alert.error)
            is EditRemoteAlert.RemoteDuplicateFailed ->
                getString(R.string.alert_duplicate_remote_failure, alert.oldRemote, alert.newRemote,
                    alert.error)
            is EditRemoteAlert.SetConfigFailed ->
                getString(R.string.alert_set_config_failure, alert.opt, alert.remote, alert.error)
        }

        Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG)
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    viewModel.acknowledgeFirstAlert()
                }
            })
            .show()
    }

    private fun confirmRenameDialog(force: Boolean) {
        if (!force && viewModel.isVfsCacheDirty) {
            VfsCacheDeletionDialogFragment.newInstance(
                getString(R.string.dialog_rename_remote_title, viewModel.remote),
            ).show(parentFragmentManager.beginTransaction(), TAG_RENAME_REMOTE_CONFIRM)
        } else {
            RemoteNameDialogFragment.newInstance(
                requireContext(),
                RemoteNameDialogAction.Rename(viewModel.remote),
                viewModel.remoteConfigs.value.keys.toTypedArray(),
            ).show(parentFragmentManager.beginTransaction(), TAG_RENAME_REMOTE)
        }
    }

    private fun confirmDelete(force: Boolean) {
        if (!force && viewModel.isVfsCacheDirty) {
            VfsCacheDeletionDialogFragment.newInstance(
                getString(R.string.dialog_delete_remote_title, viewModel.remote),
            ).show(parentFragmentManager.beginTransaction(), TAG_DELETE_REMOTE_CONFIRM)
        } else {
            viewModel.deleteRemote()
        }
    }

    private fun updateShortcuts(remoteConfigs: Map<String, RcloneRpc.RemoteConfig>) {
        val context = requireContext()

        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        var rank = 0

        for ((remote, config) in remoteConfigs) {
            if (config.hardBlockedOrDefault || !config.dynamicShortcutOrDefault) {
                continue
            }

            if (rank < maxShortcuts) {
                val shortcut = ShortcutInfoCompat.Builder(context, remote)
                    .setShortLabel(remote)
                    .setIcon(icon)
                    .setIntent(documentsUiIntent(remote))
                    .setRank(rank)
                    .build()

                shortcuts.add(shortcut)
            }

            rank += 1
        }

        if (rank > maxShortcuts) {
            Log.w(TAG, "Truncating dynamic shortcuts from $rank to $maxShortcuts")
        }

        if (!ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) {
            Log.w(TAG, "Failed to update dynamic shortcuts")
        }
    }
}
