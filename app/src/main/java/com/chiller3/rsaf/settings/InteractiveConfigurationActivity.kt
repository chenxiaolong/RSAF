/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.chiller3.rsaf.BaseActivity
import com.chiller3.rsaf.ui.theme.AppTheme

class InteractiveConfigurationActivity : BaseActivity() {
    companion object {
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_NEW = "new"

        fun createIntent(context: Context, remote: String, new: Boolean) =
            Intent(context, InteractiveConfigurationActivity::class.java).apply {
                putExtra(EXTRA_REMOTE, remote)
                putExtra(EXTRA_NEW, new)
            }
    }

    private val remote by lazy { intent.getStringExtra(EXTRA_REMOTE)!! }
    private val new by lazy { intent.getBooleanExtra(EXTRA_NEW, false) }

    private val resultData by lazy {
        Intent().apply {
            putExtra(EXTRA_REMOTE, remote)
            putExtra(EXTRA_NEW, new)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED, resultData)
    }

    @Composable
    override fun ActivityContent() {
        AppTheme {
            InteractiveConfigurationScreen(
                remote = remote,
                new = new,
                onComplete = {
                    setResult(RESULT_OK, resultData)
                    finish()
                },
                onCancel = ::finish,
            )
        }
    }
}
