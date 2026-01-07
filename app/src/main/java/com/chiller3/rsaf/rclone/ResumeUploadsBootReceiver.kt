/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ResumeUploadsBootReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = ResumeUploadsBootReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                KeepAliveService.startWithScanOnce(context)
            else -> Log.w(TAG, "Ignoring unrecognized intent: $intent")
        }
    }
}
