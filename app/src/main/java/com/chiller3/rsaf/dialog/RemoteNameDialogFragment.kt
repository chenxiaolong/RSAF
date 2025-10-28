/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneConfig

sealed interface RemoteNameDialogAction {
    fun getTitle(context: Context): String

    data object Add : RemoteNameDialogAction {
        override fun getTitle(context: Context): String =
            context.getString(R.string.dialog_add_remote_title)
    }

    data class Rename(val remote: String) : RemoteNameDialogAction {
        override fun getTitle(context: Context): String =
            context.getString(R.string.dialog_rename_remote_title, remote)
    }

    data class Duplicate(val remote: String) : RemoteNameDialogAction {
        override fun getTitle(context: Context): String =
            context.getString(R.string.dialog_duplicate_remote_title, remote)
    }
}

class RemoteNameDialogFragment : TextInputDialogFragment() {
    companion object {
        private const val ARG_REMOTE_NAMES = "blacklist"
        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS
        const val RESULT_INPUT = TextInputDialogFragment.RESULT_INPUT

        fun newInstance(context: Context, action: RemoteNameDialogAction, remoteNames: Array<String>) =
            RemoteNameDialogFragment().apply {
                arguments = toArgs(
                    action.getTitle(context),
                    context.getString(R.string.dialog_remote_name_message),
                    context.getString(R.string.dialog_remote_name_hint),
                    false,
                ).apply {
                    putStringArray(ARG_REMOTE_NAMES, remoteNames)
                }
            }
    }

    private lateinit var remoteNames: Array<String>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        remoteNames = requireArguments().getStringArray(ARG_REMOTE_NAMES)!!
        return super.onCreateDialog(savedInstanceState)
    }

    override fun isValid(input: String): Boolean =
        try {
            RcloneConfig.checkName(input)
            input !in remoteNames
        } catch (_: Exception) {
            false
        }
}