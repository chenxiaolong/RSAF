/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.chiller3.rsaf.BaseActivity
import com.chiller3.rsaf.ui.theme.AppTheme

class EditRemoteActivity : BaseActivity() {
    companion object {
        private const val EXTRA_REMOTE = "remote"

        fun createIntent(context: Context, remote: String) =
            Intent(context, EditRemoteActivity::class.java).apply {
                putExtra(EXTRA_REMOTE, remote)
            }
    }

    @Composable
    override fun ActivityContent() {
        var remote by rememberSaveable { mutableStateOf(intent.getStringExtra(EXTRA_REMOTE)!!) }

        AppTheme {
            EditRemoteScreen(
                remote = remote,
                onEditNext = { name ->
                    remote = name
                },
                onBack = ::finish,
            )
        }
    }
}
