/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VfsCacheDeletionDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_TITLE = "title"
        const val RESULT_SUCCESS = "success"

        fun newInstance(title: String) = VfsCacheDeletionDialogFragment().apply {
            arguments = bundleOf(ARG_TITLE to title)
        }
    }

    private var success: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(arguments.getString(ARG_TITLE))
            .setMessage(R.string.dialog_vfs_cache_deletion_message)
            .setPositiveButton(R.string.dialog_action_proceed_anyway) { _, _ ->
                success = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                if (Preferences(requireContext()).dialogsAtBottom) {
                    window!!.attributes.gravity = Gravity.BOTTOM
                }
            }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(RESULT_SUCCESS to success))
    }
}
