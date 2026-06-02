/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.rsaf.R
import com.chiller3.rsaf.ui.theme.Icons

@Composable
fun PasswordDialog(
    mode: ImportExportMode,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val input = rememberTextFieldState()
    val inputConfirm = rememberTextFieldState()

    AlertDialog(
        title = { Text(text = modeTitle(mode)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = modeMessage(mode))

                TogglablePasswordField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(text = modeHint(mode)) },
                )

                if (mode == ImportExportMode.EXPORT) {
                    TogglablePasswordField(
                        state = inputConfirm,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = {
                            Text(text = stringResource(R.string.dialog_export_password_confirm_hint))
                        },
                        isError = inputConfirm.text != input.text,
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSelect(input.text.toString()) },
                enabled = when (mode) {
                    ImportExportMode.IMPORT -> true
                    ImportExportMode.EXPORT -> inputConfirm.text == input.text
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

@Composable
private fun TogglablePasswordField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    label: @Composable (TextFieldLabelScope.() -> Unit)? = null,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }

    OutlinedSecureTextField(
        state = state,
        modifier = modifier,
        label = label,
        isError = isError,
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                @SuppressLint("PrivateResource")
                Icon(
                    imageVector = if (showPassword) {
                        Icons.VisibilityOff
                    } else {
                        Icons.Visibility
                    },
                    contentDescription = stringResource(
                        com.google.android.material.R.string.password_toggle_content_description,
                    ),
                )
            }
        },
        textObfuscationMode = if (showPassword) {
            TextObfuscationMode.Visible
        } else {
            TextObfuscationMode.RevealLastTyped
        },
    )
}

@Composable
private fun modeTitle(mode: ImportExportMode) = when (mode) {
    ImportExportMode.IMPORT -> stringResource(R.string.dialog_import_password_title)
    ImportExportMode.EXPORT -> stringResource(R.string.dialog_export_password_title)
}

@Composable
private fun modeMessage(mode: ImportExportMode) = when (mode) {
    ImportExportMode.IMPORT -> stringResource(R.string.dialog_import_password_message)
    ImportExportMode.EXPORT -> stringResource(R.string.dialog_export_password_message)
}

@Composable
private fun modeHint(mode: ImportExportMode) = when (mode) {
    ImportExportMode.IMPORT -> stringResource(R.string.dialog_import_password_hint)
    ImportExportMode.EXPORT -> stringResource(R.string.dialog_export_password_hint)
}
