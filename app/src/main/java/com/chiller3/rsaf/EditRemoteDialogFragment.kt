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
        const val RESULT_ACTION = "action"
        const val RESULT_REMOTE = "remote"
        // These must match the indexes in R.array.dialog_edit_remote_actions
        const val ACTION_OPEN = 0
        const val ACTION_CONFIGURE = 1
        const val ACTION_RENAME = 2
        const val ACTION_DUPLICATE = 3
        const val ACTION_DELETE = 4

        fun newInstance(remote: String): EditRemoteDialogFragment =
            EditRemoteDialogFragment().apply {
                arguments = bundleOf(ARG_REMOTE to remote)
            }
    }

    private lateinit var remote: String
    private var action = -1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        remote = arguments.getString(ARG_REMOTE)!!

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(remote)
            .setItems(R.array.dialog_edit_remote_actions) { _, i ->
                action = i
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