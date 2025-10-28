/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.databinding.DialogAuthorizeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

open class AuthorizeDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = AuthorizeDialogFragment::class.java.simpleName

        private const val ARG_CMD = "cmd"
        const val RESULT_CODE = "code"

        fun newInstance(cmd: String): AuthorizeDialogFragment =
            AuthorizeDialogFragment().apply {
                arguments = bundleOf(ARG_CMD to cmd)
            }
    }

    private lateinit var binding: DialogAuthorizeBinding
    private val viewModel: AuthorizeViewModel by viewModels()
    private var code: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()

        if (savedInstanceState == null) {
            viewModel.authorize(arguments.getString(ARG_CMD)!!)
        }

        binding = DialogAuthorizeBinding.inflate(layoutInflater)

        isCancelable = false

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_authorize_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.cancel()
            }
            .create()
            .apply {
                if (Preferences(requireContext()).dialogsAtBottom) {
                    window!!.attributes.gravity = Gravity.BOTTOM
                }

                setCanceledOnTouchOutside(false)
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.url.collect {
                    if (it == null) {
                        binding.message.text = getString(R.string.dialog_authorize_message_loading)
                    } else {
                        binding.message.text = buildString {
                            append(getString(R.string.dialog_authorize_message_url))
                            append("\n\n")
                            append(it)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.code.collect {
                    if (it != null) {
                        code = it
                        dismiss()
                    }
                }
            }
        }

        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(RESULT_CODE to code))
    }
}