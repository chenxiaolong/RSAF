/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.SharedPreferencesBackupHelper
import android.os.ParcelFileDescriptor
import android.util.Log

class ConfigBackupAgent : BackupAgentHelper() {
    companion object {
        private val TAG = ConfigBackupAgent::class.java.simpleName

        private const val KEY_SHARED_PREFS = "shared_prefs"
        private const val KEY_RCLONE_CONFIG = "rclone_config"
    }

    private lateinit var prefs: Preferences

    override fun onCreate() {
        prefs = Preferences(this)

        // This is a hack, but the naming has never changed in AOSP
        val sharedPrefsName = packageName + "_preferences"

        addHelper(KEY_SHARED_PREFS, SharedPreferencesBackupHelper(this, sharedPrefsName))
        addHelper(KEY_RCLONE_CONFIG, RcloneConfigBackupHelper())
    }

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        if (data.transportFlags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0) {
            Log.i(TAG, "Client-side encrypted backup")
        } else if (data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0) {
            Log.i(TAG, "Device-to-device transfer")
        } else {
            Log.e(TAG, "Plain-text backup is not allowed")
            return
        }

        if (prefs.allowBackup) {
            super.onBackup(oldState, data, newState)
        } else {
            Log.i(TAG, "User blocked backups; backup will be empty")
        }
    }

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) {
        Log.i(TAG, "Restoring data from version $appVersionCode")

        super.onRestore(data, appVersionCode, newState)
    }
}