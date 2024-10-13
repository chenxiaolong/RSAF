/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

open class EditRemoteDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_REMOTE = "remote"
        private const val ARG_IS_BLOCKED = "is_blocked"
        private const val ARG_HAS_SHORTCUT = "has_shortcut"
        const val RESULT_ACTION = "action"
        const val RESULT_REMOTE = "remote"

        fun newInstance(
            remote: String,
            remoteBlocked: Boolean,
            hasShortcut: Boolean
        ): EditRemoteDialogFragment =
            EditRemoteDialogFragment().apply {
                arguments = bundleOf(
                    ARG_REMOTE to remote,
                    ARG_IS_BLOCKED to remoteBlocked,
                    ARG_HAS_SHORTCUT to hasShortcut,
                )
            }
    }

    enum class Action {
        OPEN,
        BLOCK,
        UNBLOCK,
        ADD_SHORTCUT,
        REMOVE_SHORTCUT,
        CONFIGURE,
        RENAME,
        DUPLICATE,
        DELETE,
    }

    private lateinit var remote: String
    private val remoteBlocked by lazy {
        requireArguments().getBoolean(ARG_IS_BLOCKED)
    }
    private val hasShortcut by lazy {
        requireArguments().getBoolean(ARG_HAS_SHORTCUT)
    }
    private var action: Action? = null

    private val items by lazy {
        mutableListOf<Pair<Action, Int>>().apply {
            if (remoteBlocked) {
                add(Action.UNBLOCK to R.string.dialog_edit_remote_unblock_external_access)
            } else {
                add(Action.OPEN to R.string.dialog_edit_remote_open_in_documentsui)
                add(Action.BLOCK to R.string.dialog_edit_remote_block_external_access)
                if (hasShortcut) {
                    add(Action.REMOVE_SHORTCUT to R.string.dialog_edit_remote_remove_dynamic_shortcut)
                } else {
                    add(Action.ADD_SHORTCUT to R.string.dialog_edit_remote_add_dynamic_shortcut)
                }
            }
            add(Action.CONFIGURE to R.string.dialog_edit_remote_action_configure)
            add(Action.RENAME to R.string.dialog_edit_remote_action_rename)
            add(Action.DUPLICATE to R.string.dialog_edit_remote_action_duplicate)
            add(Action.DELETE to R.string.dialog_edit_remote_action_delete)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        remote = arguments.getString(ARG_REMOTE)!!

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(remote)
            .setItems(items.map { getString(it.second) }.toTypedArray()) { _, i ->
                action = items[i].first
                dismiss()
            }
            .setNegativeButton(R.string.dialog_action_cancel) { _, _ ->
                dismiss()
            }
            .create()
            .apply {
                if (Preferences(requireContext()).dialogsAtBottom) {
                    window!!.attributes.gravity = Gravity.BOTTOM
                }
            }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(
            RESULT_ACTION to action,
            RESULT_REMOTE to remote,
        ))
    }
}