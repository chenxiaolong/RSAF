/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.os.Bundle
import androidx.compose.runtime.Composable
import com.chiller3.rsaf.AppLock
import com.chiller3.rsaf.BaseActivity
import com.chiller3.rsaf.rclone.KeepAliveService
import com.chiller3.rsaf.ui.theme.AppTheme

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        KeepAliveService.startWithScanOnce(this)
    }

    @Composable
    override fun ActivityContent() {
        AppTheme {
            SettingsScreen(
                onLockNow = {
                    AppLock.onLock()
                    finishAndRemoveTask()
                },
            )
        }
    }
}
