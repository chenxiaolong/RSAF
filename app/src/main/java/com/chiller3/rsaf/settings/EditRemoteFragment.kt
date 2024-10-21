/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
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

        private val TAG_EDIT_REMOTE = "$TAG.edit_remote"
        private val TAG_RENAME_REMOTE = "$TAG.rename_remote"
        private val TAG_DUPLICATE_REMOTE = "$TAG.duplicate_remote"
    }

    override val requestTag: String = TAG

    private val viewModel: EditRemoteViewModel by viewModels()

    private lateinit var prefOpenRemote: Preference
    private lateinit var prefConfigureRemote: Preference
    private lateinit var prefRenameRemote: Preference
    private lateinit var prefDuplicateRemote: Preference
    private lateinit var prefDeleteRemote: Preference
    private lateinit var prefAllowExternalAccess: SwitchPreferenceCompat
    private lateinit var prefDynamicShortcut: SwitchPreferenceCompat

    private lateinit var remote: String

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_edit_remote, rootKey)

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

        prefDynamicShortcut = findPreference(Preferences.PREF_DYNAMIC_SHORTCUT)!!
        prefDynamicShortcut.onPreferenceChangeListener = this

        remote = requireArguments().getString(ARG_REMOTE)!!
        viewModel.setRemote(remote)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.remotes.collect {
                    Log.d(TAG, "Updating dynamic shortcuts")
                    updateShortcuts(it)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.remoteConfig.collect {
                    if (it != null) {
                        prefOpenRemote.isEnabled = it.allowExternalAccess
                        prefAllowExternalAccess.isEnabled = true
                        prefAllowExternalAccess.isChecked = it.allowExternalAccess
                        prefDynamicShortcut.isEnabled = it.allowExternalAccess
                        prefDynamicShortcut.isChecked = it.dynamicShortcut
                    } else {
                        prefOpenRemote.isEnabled = false
                        prefAllowExternalAccess.isEnabled = false
                        prefDynamicShortcut.isEnabled = false
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
                        Log.d(TAG, "Finishing edit remote activity for: $remote")
                        requireActivity().finish()
                    }
                    viewModel.activityActionCompleted()
                }
            }
        }

        for (key in arrayOf(
            TAG_EDIT_REMOTE,
            TAG_RENAME_REMOTE,
            TAG_DUPLICATE_REMOTE,
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

                    viewModel.renameRemote(remote, newRemote)
                }
            }
            TAG_DUPLICATE_REMOTE -> {
                if (bundle.getBoolean(RemoteNameDialogFragment.RESULT_SUCCESS)) {
                    val newRemote = bundle.getString(RemoteNameDialogFragment.RESULT_INPUT)!!

                    viewModel.duplicateRemote(remote, newRemote)
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
                startActivity(documentsUiIntent(remote))
                return true
            }
            prefConfigureRemote -> {
                InteractiveConfigurationDialogFragment.newInstance(remote, false)
                    .show(parentFragmentManager.beginTransaction(),
                        InteractiveConfigurationDialogFragment.TAG)
                return true
            }
            prefRenameRemote -> {
                RemoteNameDialogFragment.newInstance(
                    requireContext(),
                    RemoteNameDialogAction.Rename(remote),
                    viewModel.remotes.value.keys.toTypedArray(),
                ).show(parentFragmentManager.beginTransaction(), TAG_RENAME_REMOTE)
                return true
            }
            prefDuplicateRemote -> {
                RemoteNameDialogFragment.newInstance(
                    requireContext(),
                    RemoteNameDialogAction.Duplicate(remote),
                    viewModel.remotes.value.keys.toTypedArray(),
                ).show(parentFragmentManager.beginTransaction(), TAG_DUPLICATE_REMOTE)
                return true
            }
            prefDeleteRemote -> {
                viewModel.deleteRemote(remote)
                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        // These all return false because the state is updated when the change actually happens.

        when (preference) {
            prefAllowExternalAccess -> {
                viewModel.setExternalAccess(remote, newValue as Boolean)
            }
            prefDynamicShortcut -> {
                viewModel.setDynamicShortcut(remote, newValue as Boolean)
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
            is EditRemoteAlert.UpdateExternalAccessFailed ->
                getString(R.string.alert_update_external_access_failure, alert.remote, alert.error)
            is EditRemoteAlert.UpdateDynamicShortcutFailed ->
                getString(R.string.alert_update_dynamic_shortcut_failure, alert.remote, alert.error)
        }

        Snackbar.make(requireView(), msg, Snackbar.LENGTH_LONG)
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    viewModel.acknowledgeFirstAlert()
                }
            })
            .show()
    }

    private fun updateShortcuts(remotes: Map<String, Map<String, String>>) {
        val context = requireContext()

        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        var rank = 0

        for ((remote, config) in remotes) {
            if (config[RcloneRpc.CUSTOM_OPT_BLOCKED] == "true"
                || config[RcloneRpc.CUSTOM_OPT_DYNAMIC_SHORTCUT] != "true") {
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
