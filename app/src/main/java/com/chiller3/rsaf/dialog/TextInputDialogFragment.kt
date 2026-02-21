/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.rsaf.databinding.DialogTextInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val ARG_TITLE = "title"
private const val ARG_MESSAGE = "message"
private const val ARG_HINT = "hint"
private const val ARG_CONFIRM_HINT = "confirm_hint"
private const val ARG_INPUT_TYPE = "input_type"
private const val ARG_ORIG_VALUE = "orig_value"

enum class TextInputType {
    NORMAL,
    PASSWORD,
    NUMBER,
}

data class TextInputParams(
    val inputType: TextInputType,
    val title: String,
    val message: String,
    val hint: String,
    val confirmHint: String? = null,
    val origValue: String? = null,
) {
    constructor(args: Bundle) : this(
        inputType = TextInputType.entries[args.getInt(ARG_INPUT_TYPE)],
        title = args.getString(ARG_TITLE)!!,
        message = args.getString(ARG_MESSAGE)!!,
        hint = args.getString(ARG_HINT)!!,
        confirmHint = args.getString(ARG_CONFIRM_HINT),
        origValue = args.getString(ARG_ORIG_VALUE),
    )

    fun toArgs() = bundleOf(
        ARG_INPUT_TYPE to inputType.ordinal,
        ARG_TITLE to title,
        ARG_MESSAGE to message,
        ARG_HINT to hint,
        ARG_CONFIRM_HINT to confirmHint,
        ARG_ORIG_VALUE to origValue,
    )
}

abstract class TextInputDialogFragment<T> : DialogFragment() {
    companion object {
        protected const val RESULT_SUCCESS = "success"
        protected const val RESULT_ORIG_VALUE = "orig_value"
        protected const val RESULT_VALUE = "value"
    }

    private lateinit var binding: DialogTextInputBinding
    private lateinit var params: TextInputParams
    private var success: Boolean = false
    private var value: T? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        params = TextInputParams(requireArguments())

        binding = DialogTextInputBinding.inflate(layoutInflater)
        binding.message.text = params.message

        binding.textLayout.hint = params.hint
        binding.confirmTextLayout.hint = params.confirmHint
        binding.confirmTextLayout.isVisible = params.confirmHint != null

        val inputType = when (params.inputType) {
            TextInputType.NORMAL ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            TextInputType.PASSWORD ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            TextInputType.NUMBER -> InputType.TYPE_CLASS_NUMBER
        }
        binding.text.inputType = inputType
        binding.confirmText.inputType = inputType

        binding.text.addTextChangedListener {
            value = translateInput(it.toString())

            refreshOkButtonEnabledState()
        }
        binding.confirmText.addTextChangedListener {
            refreshOkButtonEnabledState()
        }

        if (savedInstanceState == null) {
            binding.text.setText(params.origValue)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(params.title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                success = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        refreshOkButtonEnabledState()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(
            RESULT_SUCCESS to success,
            RESULT_ORIG_VALUE to params.origValue,
            RESULT_VALUE to value,
        ).also(::updateResult))
    }

    private fun refreshOkButtonEnabledState() {
        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = value != null
                && (params.confirmHint == null
                || binding.text.text?.toString() == binding.confirmText.text?.toString())
    }

    abstract fun translateInput(input: String): T?

    open fun updateResult(result: Bundle) {}
}
