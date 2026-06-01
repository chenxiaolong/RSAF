/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.system.ErrnoException
import android.text.Annotation
import android.text.SpannedString
import android.view.View
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.chiller3.rsaf.R
import com.chiller3.rsaf.extension.toSingleLineString
import com.chiller3.rsaf.rclone.VfsCache
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

private fun findDialogWindow(view: View): Window {
    var current: View? = view

    while (current != null) {
        if (current is DialogWindowProvider) {
            return current.window
        }

        current = current.parent as? View
    }

    throw IllegalStateException("Dialog window not found: $view")
}

@Composable
private fun rememberFixInputResize(): Int {
    val view = LocalView.current
    val window = findDialogWindow(view)

    // The dialog can resize due to the multiline input. Make sure the dialog doesn't get hidden
    // behind the IME.
    //
    // SOFT_INPUT_ADJUST_RESIZE works reliably, but is deprecated. The normal way of using
    // setOnApplyWindowInsetsListener() + adjusting padding does not work for the dialog's
    // DecorView. It causes terrible layout issues.
    //
    // Adding WindowInsetsCompat.Type.ime() to window!!.attributes.fitInsetsTypes does not work in
    // all cases either. It kind of works until you add enough lines to max out the height, rotate
    // to landscape, rotate back to portrait, and then tap on the text box. The dialog doesn't
    // resize to the correct size until the keyboard is closed and reopened.
    //
    // AOSP still uses this in several places, like the Settings app, so it should stay working.
    return remember(window) {
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Just to avoid rerunning on every composition.
        0
    }
}

@Composable
fun VfsOptionsDialog(
    remote: String,
    initialOptions: Map<String, String>,
    onSelect: (Map<String, String>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialText = remember {
        buildString {
            for ((key, value) in initialOptions) {
                if (isNotEmpty()) {
                    append('\n')
                }
                append("$key=$value")
            }
        }
    }
    val input = rememberTextFieldState(initialText = initialText)
    val options = tryParseInput(input.text.toString())

    AlertDialog(
        title = { Text(text = stringResource(R.string.vfs_options_title, remote)) },
        text = {
            // This is here because we need to run with LocalView being the dialog view.
            rememberFixInputResize()

            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = buildMessage())

                OutlinedTextField(
                    state = input,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    isError = options is VfsOptionsParse.Error,
                    supportingText = {
                        if (options is VfsOptionsParse.Error && options.message != null) {
                            Text(text = options.message)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2),
                )

                Text(text = stringResource(R.string.vfs_options_reload_warning))
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSelect((options as VfsOptionsParse.Value).options, true) },
                enabled = options is VfsOptionsParse.Value,
            ) {
                Text(text = stringResource(R.string.vfs_options_save_and_reload))
            }

            TextButton(
                onClick = { onSelect((options as VfsOptionsParse.Value).options, false) },
                enabled = options is VfsOptionsParse.Value,
            ) {
                Text(text = stringResource(R.string.vfs_options_save_only))
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
private fun buildMessage(): AnnotatedString {
    val resources = LocalResources.current

    val origMessage = resources.getText(R.string.vfs_options_message) as SpannedString
    val message = StringBuilder(origMessage)
    val origAnnotations = origMessage.getSpans(0, origMessage.length, Annotation::class.java)
    val newAnnotations = mutableListOf<AnnotatedString.Range<AnnotatedString.Annotation>>()

    for (annotation in origAnnotations) {
        val start = origMessage.getSpanStart(annotation)
        val end = origMessage.getSpanEnd(annotation)

        if (annotation.key == "type" && annotation.value == "rclone_vfs_docs") {
            newAnnotations.add(
                AnnotatedString.Range(
                    LinkAnnotation.Url("https://rclone.org/commands/rclone_mount/"),
                    start,
                    end,
                )
            )
        } else {
            throw IllegalStateException("Invalid annotation: $annotation")
        }
    }

    return AnnotatedString(message.toString(), newAnnotations)
}

private sealed interface VfsOptionsParse {
    data class Value(val options: Map<String, String>) : VfsOptionsParse

    data class Error(val message: String?) : VfsOptionsParse
}

@Composable
private fun tryParseInput(input: String): VfsOptionsParse {
    try {
        val options = mutableMapOf<String, String>()

        for (line in input.splitToSequence('\n')) {
            if (line.trim().isEmpty()) {
                continue
            }

            val pieces = line.split('=', limit = 2)

            // Treat an incomplete line as just the key. rcbridge will show a better error message
            // for unknown keys.
            options[pieces[0]] = if (pieces.size > 1) {
                pieces[1]
            } else {
                ""
            }
        }

        VfsCache.getVfsOptions(options)
        return VfsOptionsParse.Value(options)
    } catch (e: Exception) {
        val message = if (e is ErrnoException) {
            e.cause!!.message
        } else {
            e.toSingleLineString()
        }

        return VfsOptionsParse.Error(message)
    }
}
