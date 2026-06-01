/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.rsaf.R

@Composable
fun AuthorizeDialog(
    cmd: String,
    onReceive: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scopedOwner = rememberViewModelStoreOwner()

    CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
        val viewModel: AuthorizeViewModel = viewModel()
        viewModel.authorize(cmd)

        val url by viewModel.url.collectAsStateWithLifecycle()

        AlertDialog(
            title = { Text(text = stringResource(R.string.dialog_authorize_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                    Text(text = urlMessage(url))

                    LinearWavyProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            onDismissRequest = onDismiss,
            confirmButton = {},
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

        LaunchedEffect(Unit) {
            viewModel.code.collect {
                if (it != null) {
                    onReceive(it)
                }
            }
        }
    }
}

@Composable
private fun urlMessage(url: String?) = if (url == null) {
    AnnotatedString(stringResource(R.string.dialog_authorize_message_loading))
} else {
    buildAnnotatedString {
        append(stringResource(R.string.dialog_authorize_message_url))
        append("\n\n")
        withLink(LinkAnnotation.Url(url)) {
            append(url)
        }
    }
}
