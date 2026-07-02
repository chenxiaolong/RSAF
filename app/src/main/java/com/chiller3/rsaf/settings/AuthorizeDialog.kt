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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.AuthorizeService
import com.chiller3.rsaf.rclone.Authorizer
import com.chiller3.rsaf.rclone.rememberAuthorizeWatcher

@Composable
fun AuthorizeDialog(
    cmd: String,
    onReceive: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    val latestOnReceive by rememberUpdatedState(onReceive)

    rememberAuthorizeWatcher(listener = object : Authorizer.AuthorizeListener {
        override fun onAuthorizeUrl(url: String) {
            authorizeUrl = url
        }

        override fun onAuthorizeCode(code: String) {
            latestOnReceive(code)
        }
    })

    LaunchedEffect(Unit) {
        context.startForegroundService(AuthorizeService.createStartIntent(context, cmd))
    }

    val cancelAndDismiss = {
        context.startService(AuthorizeService.createCancelIntent(context))
        onDismiss()
    }

    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_authorize_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = urlMessage(authorizeUrl))

                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        onDismissRequest = cancelAndDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = cancelAndDismiss) {
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
