/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.annotation.SuppressLint
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
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.databinding.DialogTextInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class InactivityTimeoutDialogFragment : DialogFragment() {
    companion object {
        val TAG = InactivityTimeoutDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = "success"
    }

    private lateinit var prefs: Preferences
    private lateinit var binding: DialogTextInputBinding
    private var duration: Int? = null
    private var success: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        prefs = Preferences(requireContext())

        binding = DialogTextInputBinding.inflate(layoutInflater)
        binding.message.text = getString(R.string.dialog_inactivity_timeout_message)
        binding.text.inputType = InputType.TYPE_CLASS_NUMBER
        binding.text.addTextChangedListener {
            duration = try {
                val seconds = it.toString().toInt()
                if (seconds >= Preferences.MIN_INACTIVITY_TIMEOUT) {
                    seconds
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

            refreshOkButtonEnabledState()
        }
        if (savedInstanceState == null) {
            @SuppressLint("SetTextI18n")
            binding.text.setText(prefs.inactivityTimeout.toString())
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_inactivity_timeout_title)
            .setView(binding.root)
            .setPositiveButton(R.string.dialog_action_ok) { _, _ ->
                prefs.inactivityTimeout = duration!!
                success = true
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

        setFragmentResult(tag!!, bundleOf(RESULT_SUCCESS to success))
    }

    private fun refreshOkButtonEnabledState() {
        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            duration != null
    }
}
