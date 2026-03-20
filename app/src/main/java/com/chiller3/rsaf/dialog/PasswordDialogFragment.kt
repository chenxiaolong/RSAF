/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.dialog

import android.content.Context
import android.os.Bundle
import com.chiller3.rsaf.R
import com.chiller3.rsaf.settings.ImportExportMode

class PasswordDialogFragment : TextInputDialogFragment<String>() {
    companion object {
        val TAG: String = PasswordDialogFragment::class.java.simpleName

        const val RESULT_SUCCESS = TextInputDialogFragment.RESULT_SUCCESS
        const val RESULT_PASSWORD = "password"

        fun newInstance(context: Context, mode: ImportExportMode) =
            PasswordDialogFragment().apply {
                arguments = TextInputParams(
                    inputType = TextInputType.PASSWORD,
                    title = when (mode) {
                        ImportExportMode.IMPORT ->
                            context.getString(R.string.dialog_import_password_title)
                        ImportExportMode.EXPORT ->
                            context.getString(R.string.dialog_export_password_title)
                    },
                    message = when (mode) {
                        ImportExportMode.IMPORT ->
                            context.getString(R.string.dialog_import_password_message)
                        ImportExportMode.EXPORT ->
                            context.getString(R.string.dialog_export_password_message)
                    },
                    hint = when (mode) {
                        ImportExportMode.IMPORT ->
                            context.getString(R.string.dialog_import_password_hint)
                        ImportExportMode.EXPORT ->
                            context.getString(R.string.dialog_export_password_hint)
                    },
                    confirmHint = when (mode) {
                        ImportExportMode.IMPORT -> null
                        ImportExportMode.EXPORT ->
                            context.getString(R.string.dialog_export_password_confirm_hint)
                    },
                ).toArgs()
            }
    }

    override fun translateInput(input: String): String = input

    override fun updateResult(result: Bundle, value: String?) {
        result.putString(RESULT_PASSWORD, value)
    }
}
