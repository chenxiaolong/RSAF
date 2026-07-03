/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chiller3.rsaf.ui.theme.Icons

data class AppScreenParams(
    val contentPadding: PaddingValues,
    val snackbarHostState: SnackbarHostState,
)

@Composable
fun AppScreen(
    title: @Composable () -> Unit,
    onBack: (() -> Unit)? = null,
    backIsExit: Boolean = false,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (AppScreenParams) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    onBack?.let { onClick ->
                        IconButton(
                            onClick = onClick,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = PreferenceDefaults.scrolledContainerColor,
                            ),
                        ) {
                            if (backIsExit) {
                                Icon(
                                    imageVector = Icons.Close,
                                    contentDescription = stringResource(android.R.string.cancel)
                                )
                            } else {
                                @SuppressLint("PrivateResource")
                                Icon(
                                    imageVector = Icons.AutoMirrored.ArrowBack,
                                    contentDescription = stringResource(
                                        androidx.appcompat.R.string.abc_action_bar_up_description,
                                    ),
                                )
                            }
                        }
                    }
                },
                colors = PreferenceDefaults.appBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = PreferenceDefaults.containerColor,
        contentWindowInsets = contentWindowInsets,
    ) { contentPadding ->
        val outerPadding = contentPadding.copy(start = 0.dp, end = 0.dp, bottom = 0.dp)
        val innerPadding = contentPadding.copy(top = 0.dp)

        Box(modifier = Modifier.padding(outerPadding)) {
            content(AppScreenParams(
                contentPadding = innerPadding,
                snackbarHostState = snackbarHostState,
            ))
        }
    }
}
