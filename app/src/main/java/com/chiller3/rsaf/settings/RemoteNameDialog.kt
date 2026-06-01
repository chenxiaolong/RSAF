/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.res.Resources
import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneConfig
import kotlinx.parcelize.Parcelize

@Parcelize
sealed interface RemoteNameDialogAction : Parcelable {
    fun getTitle(resources: Resources): String

    @Parcelize
    data object Add : RemoteNameDialogAction {
        override fun getTitle(resources: Resources): String =
            resources.getString(R.string.dialog_add_remote_title)
    }

    @Parcelize
    data class Rename(val remote: String) : RemoteNameDialogAction {
        override fun getTitle(resources: Resources): String =
            resources.getString(R.string.dialog_rename_remote_title, remote)
    }

    @Parcelize
    data class Duplicate(val remote: String) : RemoteNameDialogAction {
        override fun getTitle(resources: Resources): String =
            resources.getString(R.string.dialog_duplicate_remote_title, remote)
    }
}

@Composable
fun RemoteNameDialog(
    action: RemoteNameDialogAction,
    existingRemotes: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current

    val input = rememberTextFieldState()
    val name = tryParseInput(input.text.toString(), existingRemotes)

    AlertDialog(
        title = { Text(text = action.getTitle(resources)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = stringResource(R.string.dialog_remote_name_message))

                OutlinedTextField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text(text = stringResource(R.string.dialog_remote_name_hint)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSelect(name!!) },
                enabled = name != null,
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
private fun tryParseInput(input: String, existingRemotes: List<String>): String? {
    try {
        RcloneConfig.checkName(input)
        if (input !in existingRemotes) {
            return input
        }
    } catch (_: Exception) {
        // Ignore.
    }

    return null
}
