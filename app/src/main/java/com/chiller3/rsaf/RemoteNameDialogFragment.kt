package com.chiller3.rsaf

import android.app.Dialog
import android.os.Bundle

class RemoteNameDialogFragment : TextInputDialogFragment() {
    companion object {
        private const val ARG_REMOTE_NAMES = "blacklist"
        const val RESULT_ARGS = TextInputDialogFragment.RESULT_ARGS
        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS
        const val RESULT_INPUT = TextInputDialogFragment.RESULT_INPUT

        fun newInstance(title: String, message: String, hint: String, remoteNames: Array<String>):
                RemoteNameDialogFragment =
            RemoteNameDialogFragment().apply {
                arguments = toArgs(title, message, hint, false).apply {
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
        } catch (e: Exception) {
            false
        }
}