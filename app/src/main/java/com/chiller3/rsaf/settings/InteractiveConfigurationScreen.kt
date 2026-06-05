/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.system.ErrnoException
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneConfig
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.ui.AppScreen
import com.chiller3.rsaf.ui.PreferenceCategory
import com.chiller3.rsaf.ui.PreferenceColumn
import com.chiller3.rsaf.ui.PreferenceDefaults
import com.chiller3.rsaf.ui.RadioPreference
import com.chiller3.rsaf.ui.betterSegmentedShapes
import com.chiller3.rsaf.ui.copy
import com.chiller3.rsaf.ui.theme.AppTheme
import com.chiller3.rsaf.ui.theme.Icons
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "InteractiveConfigurationScreen"

@Composable
fun InteractiveConfigurationScreen(
    remote: String,
    new: Boolean,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    viewModel: InteractiveConfigurationViewModel = viewModel(),
) {
    viewModel.init(remote)

    val question by viewModel.question.collectAsStateWithLifecycle()
    val hasPrevious by viewModel.hasPrevious.collectAsStateWithLifecycle()

    val title = if (new) {
        stringResource(R.string.ic_title_add_remote, remote)
    } else {
        stringResource(R.string.ic_title_edit_remote, remote)
    }

    AppScreen(
        title = { Text(text = title) },
        onBack = onCancel,
        backIsExit = true,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
    ) { params ->
        question?.let { (error, option) ->
            InteractiveConfigurationContent(
                error = error,
                option = option,
                hasPrevious = hasPrevious,
                onPrevQuestion = {
                    viewModel.goBack()
                },
                onNextQuestion = { answer ->
                    viewModel.submit(answer)
                },
                contentPadding = params.contentPadding,
            )
        }
    }

    BackHandler(
        enabled = hasPrevious,
        onBack = { viewModel.goBack() },
    )

    val latestOnComplete by rememberUpdatedState(onComplete)

    LaunchedEffect(Unit) {
        viewModel.run.collect {
            if (!it) {
                // No more questions. We can just exit because changes are immediately
                // committed upon submission.
                latestOnComplete()
            }
        }
    }
}

private fun parseAnswer(option: RcloneRpc.ProviderOption, input: String): String? =
    if (option.exclusive && !option.examples.any { it.value == input }) {
        null
    } else if (option.required && input.isEmpty()) {
        null
    } else {
        input
    }

private fun annotateLinks(msg: String): AnnotatedString {
    val spanned = SpannableString(msg)
    if (!Linkify.addLinks(spanned, Linkify.WEB_URLS)) {
        return AnnotatedString(msg)
    }

    val origAnnotations = spanned.getSpans(0, spanned.length, URLSpan::class.java)
    val newAnnotations = mutableListOf<AnnotatedString.Range<AnnotatedString.Annotation>>()

    for (annotation in origAnnotations) {
        val start = spanned.getSpanStart(annotation)
        val end = spanned.getSpanEnd(annotation)

        newAnnotations.add(
            AnnotatedString.Range(
                LinkAnnotation.Url(annotation.url),
                start,
                end,
            )
        )
    }

    return AnnotatedString(msg, newAnnotations)
}

/** Replace newlines with spaces unless there are multiple newlines in a row. */
private fun reflowString(msg: String): String =
    msg.replace("([^\\n])\\n([^\\n]|$)".toRegex(), "$1 $2")

private fun tryReveal(text: String, isPassword: Boolean): String {
    var value = text

    if (isPassword) {
        try {
            value = RcloneConfig.revealPassword(text).value
        } catch (e: ErrnoException) {
            Log.w(TAG, "Failed to reveal password", e)
        }
    }

    return value
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("PrivateResource")
@Composable
private fun InteractiveConfigurationContent(
    error: String?,
    option: RcloneRpc.ProviderOption,
    hasPrevious: Boolean,
    onPrevQuestion: () -> Unit,
    onNextQuestion: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val isPreview = LocalInspectionMode.current

    val input = rememberTextFieldState()

    // When going back to the previous question, we need to discard state and reload the option,
    // which may have a different value now.
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    val currentOrDefault = remember(epoch, option.name) {
        if (option.value.isNotEmpty()) {
            tryReveal(option.value, option.isPassword && !isPreview)
        } else {
            tryReveal(option.default, option.isPassword && !isPreview)
        }
    }
    var loadedOnce by rememberSaveable(epoch, option.name) { mutableStateOf(false) }
    if (!loadedOnce) {
        LaunchedEffect(epoch, option.name) {
            input.edit {
                replace(0, length, currentOrDefault)
            }
            loadedOnce = true
        }
    }

    val answer = parseAnswer(option, input.text.toString())

    var showAuthorizeDialog by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val maxWidthModifier = Modifier
            .widthIn(max = PreferenceDefaults.MaxWidth)
            .fillMaxWidth()

        PreferenceColumn(
            modifier = Modifier.fillMaxSize().weight(weight = 1f),
            contentPadding = contentPadding.copy(bottom = 0.dp),
        ) {
            item("message") {
                val message = buildAnnotatedString {
                    if (error != null) {
                        append(reflowString(error))
                        append("\n\n")
                    }
                    append(annotateLinks(reflowString(option.help)))
                }

                SelectionContainer(modifier = maxWidthModifier) {
                    Text(text = message)
                }
            }

            if (!option.exclusive) {
                item("input") {
                    AnswerTextField(
                        option = option,
                        state = input,
                        answer = answer,
                        modifier = maxWidthModifier.padding(top = 8.dp)
                    )
                }
            }

            if (option.isAuthorize) {
                item("authorize") {
                    Button(
                        onClick = { showAuthorizeDialog = option.authorizeCmd },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.dialog_action_authorize))
                    }
                }
            }

            if (option.examples.isNotEmpty()) {
                item("examples_divider") {
                    HorizontalDivider(
                        modifier = maxWidthModifier
                            .padding(vertical = PreferenceDefaults.HorizontalPadding),
                    )
                }

                if (!option.exclusive) {
                    item("examples_header") {
                        PreferenceCategory(
                            title = { Text(text = stringResource(R.string.ic_header_examples)) },
                        )
                    }
                }

                itemsIndexed(
                    option.examples,
                    key = { _, e -> "example_${e.value}" },
                ) { index, example ->
                    val isSelected = answer == example.value
                    val onClick = { input.edit { replace(0, length, example.value) } }

                    RadioPreference(
                        selected = isSelected,
                        onClick = onClick,
                        shapes = betterSegmentedShapes(index, option.examples.size),
                        title = { Text(text = example.value) },
                        summary = if (example.help != example.value) {
                            { Text(text = example.help) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Box(modifier = Modifier.padding(contentPadding + PreferenceDefaults.ListPadding)) {
            HorizontalDivider(modifier = maxWidthModifier)

            NavigationButtons(
                hasPrevious = hasPrevious,
                answer = answer,
                onPrevQuestion = {
                    epoch++
                    onPrevQuestion()
                },
                onNextQuestion = onNextQuestion,
                modifier = maxWidthModifier,
            )
        }
    }

    showAuthorizeDialog?.let { cmd ->
        AuthorizeDialog(
            cmd = cmd,
            onReceive = {
                input.edit { replace(0, length, it) }
                showAuthorizeDialog = null
            },
            onDismiss = {
                showAuthorizeDialog = null
            },
        )
    }
}

@Composable
private fun AnswerTextField(
    option: RcloneRpc.ProviderOption,
    state: TextFieldState,
    answer: String?,
    modifier: Modifier = Modifier,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val supportingText = if (option.required) {
        stringResource(R.string.ic_text_box_helper_required)
    } else {
        stringResource(R.string.ic_text_box_helper_not_required)
    }

    if (option.isPassword) {
        OutlinedSecureTextField(
            state = state,
            modifier = modifier,
            label = { Text(text = option.name) },
            isError = answer == null,
            supportingText = { Text(text = supportingText) },
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    } else {
        OutlinedTextField(
            state = state,
            modifier = modifier,
            label = { Text(text = option.name) },
            isError = answer == null,
            supportingText = { Text(text = supportingText) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
    }
}

@Composable
private fun NavigationButtons(
    hasPrevious: Boolean,
    answer: String?,
    onPrevQuestion: () -> Unit,
    onNextQuestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onPrevQuestion,
            modifier = Modifier
                .padding(all = 8.dp)
                .align(alignment = Alignment.CenterStart),
            enabled = hasPrevious,
        ) {
            Text(text = stringResource(R.string.dialog_action_back))
        }

        Button(
            onClick = { onNextQuestion(answer!!) },
            modifier = Modifier
                .padding(all = 8.dp)
                .align(alignment = Alignment.CenterEnd),
            enabled = answer != null,
        ) {
            Text(text = stringResource(R.string.dialog_action_next))
        }
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewQuestionArbitrary() {
    val remote = "test"
    val option = RcloneRpc.ProviderOption(
        JSONObject()
            .put("Name", "test")
            .put("FieldName", "")
            .put("Help", "This is an open ended question with a https://localhost link.")
            .put("DefaultStr", "")
            .put("ValueStr", "")
            .put("Examples", JSONArray().apply {
                put(
                    JSONObject()
                        .put("Value", "a")
                        .put("Help", "First option")
                        .put("Provider", "")
                )
                put(
                    JSONObject()
                        .put("Value", "b")
                        .put("Help", "Second option")
                        .put("Provider", "")
                )
            })
            .put("Hide", 0)
            .put("Required", true)
            .put("IsPassword", false)
            .put("NoPrefix", false)
            .put("Advanced", false)
            .put("Exclusive", false)
            .put("Sensitive", false)
            .put("Type", "string")
    )

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.ic_title_add_remote, remote)) },
            onBack = {},
            backIsExit = true,
        ) { params ->
            InteractiveConfigurationContent(
                error = null,
                option = option,
                hasPrevious = true,
                onPrevQuestion = {},
                onNextQuestion = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewQuestionExclusive() {
    val remote = "test"
    val option = RcloneRpc.ProviderOption(
        JSONObject()
            .put("Name", "test")
            .put("FieldName", "")
            .put("Help", "This is a question with fixed choices.")
            .put("DefaultStr", "b")
            .put("ValueStr", "")
            .put("Examples", JSONArray().apply {
                put(
                    JSONObject()
                        .put("Value", "a")
                        .put("Help", "First option")
                        .put("Provider", "")
                )
                put(
                    JSONObject()
                        .put("Value", "b")
                        .put("Help", "Second option")
                        .put("Provider", "")
                )
            })
            .put("Hide", 0)
            .put("Required", true)
            .put("IsPassword", false)
            .put("NoPrefix", false)
            .put("Advanced", false)
            .put("Exclusive", true)
            .put("Sensitive", false)
            .put("Type", "string")
    )

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.ic_title_add_remote, remote)) },
            onBack = {},
            backIsExit = true,
        ) { params ->
            InteractiveConfigurationContent(
                error = null,
                option = option,
                hasPrevious = false,
                onPrevQuestion = {},
                onNextQuestion = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewQuestionPassword() {
    val remote = "test"
    val option = RcloneRpc.ProviderOption(
        JSONObject()
            .put("Name", "test")
            .put("FieldName", "")
            .put("Help", "This is a question for a password.")
            .put("DefaultStr", "hunter2")
            .put("ValueStr", "")
            .put("Examples", JSONArray())
            .put("Hide", 0)
            .put("Required", true)
            .put("IsPassword", true)
            .put("NoPrefix", false)
            .put("Advanced", false)
            .put("Exclusive", false)
            .put("Sensitive", false)
            .put("Type", "string")
    )

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.ic_title_edit_remote, remote)) },
            onBack = {},
            backIsExit = true,
        ) { params ->
            InteractiveConfigurationContent(
                error = "This is an error message from rclone.",
                option = option,
                hasPrevious = true,
                onPrevQuestion = {},
                onNextQuestion = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
