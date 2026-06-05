/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalUnsignedTypes::class)

package com.chiller3.rsaf.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.chiller3.rsaf.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ErrorDetailsDialog(
    message: String?,
    onDismiss: () -> Unit,
    showCopy: Boolean = true,
) {
    val context = LocalContext.current

    AlertDialog(
        title = {
            Text(text = stringResource(R.string.dialog_error_details_title))
        },
        text = {
            message?.let {
                SelectionContainer {
                    Text(text = it)
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            if (message != null && showCopy) {
                TextButton(
                    onClick = {
                        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                        val clipData = ClipData.newPlainText("message", message)

                        clipboardManager.setPrimaryClip(clipData)
                    },
                ) {
                    Text(text = stringResource(android.R.string.copy))
                }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}
