/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MessageDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = MessageDialogFragment::class.java.simpleName

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"

        fun newInstance(title: String?, message: String?): MessageDialogFragment =
            MessageDialogFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_MESSAGE to message,
                )
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val title = arguments.getString(ARG_TITLE)
        val message = arguments.getString(ARG_MESSAGE)?.trimEnd()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(android.R.string.copy) { _, _ ->
                val clipboardManager = requireContext()
                    .getSystemService(ClipboardManager::class.java)
                val clipData = ClipData.newPlainText("message", message)

                clipboardManager.setPrimaryClip(clipData)
            }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
            }
    }
}
