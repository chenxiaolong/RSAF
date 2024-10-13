/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.rsaf.databinding.DialogTextInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

open class TextInputDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_HINT = "hint"
        private const val ARG_IS_PASSWORD = "is_password"
        const val RESULT_ARGS = "args"
        const val RESULT_SUCCESS = "success"
        const val RESULT_INPUT = "input"

        @JvmStatic
        protected fun toArgs(title: String, message: String, hint: String, isPassword: Boolean) =
            bundleOf(
                ARG_TITLE to title,
                ARG_MESSAGE to message,
                ARG_HINT to hint,
                ARG_IS_PASSWORD to isPassword,
            )

        fun newInstance(title: String, message: String, hint: String, isPassword: Boolean):
                TextInputDialogFragment =
            TextInputDialogFragment().apply {
                arguments = toArgs(title, message, hint, isPassword)
            }
    }

    private lateinit var binding: DialogTextInputBinding
    private var success: Boolean = false
    private var input: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()

        binding = DialogTextInputBinding.inflate(layoutInflater)
        binding.message.text = arguments.getString(ARG_MESSAGE)
        binding.textLayout.hint = arguments.getString(ARG_HINT)
        binding.text.inputType = InputType.TYPE_CLASS_TEXT or
            if (arguments.getBoolean(ARG_IS_PASSWORD)) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                0
            }
        binding.text.addTextChangedListener {
            refreshOkButtonEnabledState()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(arguments.getString(ARG_TITLE))
            .setView(binding.root)
            .setPositiveButton(R.string.dialog_action_ok) { _, _ ->
                success = true
                input = binding.text.text.toString()
            }
            .setNegativeButton(R.string.dialog_action_cancel, null)
            .create()
            .apply {
                if (Preferences(requireContext()).dialogsAtBottom) {
                    window!!.attributes.gravity = Gravity.BOTTOM
                }
            }
    }

    override fun onStart() {
        super.onStart()
        refreshOkButtonEnabledState()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(
            RESULT_ARGS to arguments,
            RESULT_SUCCESS to success,
            RESULT_INPUT to input,
        ))
    }

    private fun refreshOkButtonEnabledState() {
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
            isValid(binding.text.text.toString())
    }

    open fun isValid(input: String): Boolean = true
}