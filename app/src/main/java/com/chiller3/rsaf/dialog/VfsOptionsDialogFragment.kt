/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.system.ErrnoException
import android.text.Annotation
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.SpannedString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.R
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.databinding.DialogVfsOptionsBinding
import com.chiller3.rsaf.extension.toSingleLineString
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.rclone.VfsCache
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VfsOptionsDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = VfsOptionsDialogFragment::class.java.simpleName

        private const val ARG_REMOTE = "remote"
        const val RESULT_REMOTE = ARG_REMOTE

        fun newInstance(remote: String): VfsOptionsDialogFragment =
            VfsOptionsDialogFragment().apply {
                arguments = bundleOf(ARG_REMOTE to remote)
            }
    }

    private lateinit var binding: DialogVfsOptionsBinding
    private lateinit var remote: String
    private lateinit var config: RcloneRpc.RemoteConfig
    private var overrides: Map<String, String>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        remote = arguments.getString(ARG_REMOTE)!!
        config = RcloneRpc.remoteConfigs[remote]!!

        binding = DialogVfsOptionsBinding.inflate(layoutInflater)

        binding.message.movementMethod = LinkMovementMethod.getInstance()
        binding.message.text = buildMessage()

        binding.text.addTextChangedListener {
            try {
                val newOverrides = mutableMapOf<String, String>()

                for (line in it.toString().splitToSequence('\n')) {
                    if (line.trim().isEmpty()) {
                        continue
                    }

                    val pieces = line.split('=', limit = 2)

                    // Treat an incomplete line as just the key. rcbridge will show a better error
                    // message for unknown keys.
                    newOverrides[pieces[0]] = if (pieces.size > 1) {
                        pieces[1]
                    } else {
                        ""
                    }
                }

                VfsCache.getVfsOptions(newOverrides)
                overrides = newOverrides

                binding.textLayout.error = null
                // Don't keep the layout space for the error message reserved.
                binding.textLayout.isErrorEnabled = false
            } catch (e: Exception) {
                overrides = null

                binding.textLayout.error = if (e is ErrnoException) {
                    e.cause!!.message
                } else {
                    e.toSingleLineString()
                }
            }

            refreshButtonsEnabledState()
        }

        if (savedInstanceState == null) {
            binding.text.setText(buildString {
                for ((key, value) in config.vfsOptions) {
                    if (isNotEmpty()) {
                        append('\n')
                    }
                    append("$key=$value")
                }
            })
        }

        isCancelable = false

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.vfs_options_title, remote))
            .setView(binding.root)
            .setPositiveButton(R.string.vfs_options_save_and_reload) { _, _ ->
                save(true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.vfs_options_save_only) { _, _ ->
                save(false)
            }
            .create()
            .apply {
                if (Preferences(requireContext()).dialogsAtBottom) {
                    window!!.attributes.gravity = Gravity.BOTTOM
                }

                // The dialog can resize due to the multiline input. Make sure the dialog doesn't
                // get hidden behind the IME.
                //
                // SOFT_INPUT_ADJUST_RESIZE works reliably, but is deprecated. The normal way of
                // using setOnApplyWindowInsetsListener() + adjusting padding does not work for the
                // dialog's DecorView. It causes terrible layout issues.
                //
                // Adding WindowInsetsCompat.Type.ime() to window!!.attributes.fitInsetsTypes does
                // not work in all cases either. It kind of works until you add enough lines to max
                // out the height, rotate to landscape, rotate back to portrait, and then tap on the
                // text box. The dialog doesn't resize to the correct size until the keyboard is
                // closed and reopened.
                //
                // AOSP still uses this in several places, like the Settings app, so it should stay
                // working.
                @Suppress("DEPRECATION")
                window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

                setCanceledOnTouchOutside(false)
            }

        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        // Just to signal completion to the parent.
        setFragmentResult(tag!!, bundleOf(RESULT_REMOTE to remote))
    }

    private fun buildMessage(): SpannableStringBuilder {
        val origMessage = getText(R.string.vfs_options_message) as SpannedString
        val message = SpannableStringBuilder(origMessage)
        val annotations = message.getSpans(0, origMessage.length, Annotation::class.java)

        for (annotation in annotations) {
            val start = message.getSpanStart(annotation)
            val end = message.getSpanEnd(annotation)

            if (annotation.key == "type" && annotation.value == "rclone_vfs_docs") {
                message.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val uri = "https://rclone.org/commands/rclone_mount/".toUri()
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    },
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                throw IllegalStateException("Invalid annotation: $annotation")
            }
        }

        return message
    }

    private fun refreshButtonsEnabledState() {
        val dialog = dialog as AlertDialog?
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = overrides != null
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = overrides != null
    }

    private fun save(reload: Boolean) {
        RcloneRpc.setRemoteConfig(remote, config.copy(vfsOptions = overrides!!))

        if (reload) {
            Rcbridge.rbCacheClearRemote("$remote:", false)
        }
    }
}
