/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlin.math.max

class Preferences(private val context: Context) {
    companion object {
        const val CATEGORY_PERMISSIONS = "permissions"
        const val CATEGORY_CONFIGURATION = "configuration"
        const val CATEGORY_DEBUG = "debug"
        const val CATEGORY_REMOTES = "remotes"

        // Main preferences
        const val PREF_ADD_FILE_EXTENSION = "add_file_extension"
        const val PREF_ALLOW_BACKUP = "allow_backup"
        const val PREF_DIALOGS_AT_BOTTOM = "dialogs_at_bottom"
        const val PREF_LOCAL_STORAGE_ACCESS = "local_storage_access"
        const val PREF_PRETEND_LOCAL = "pretend_local"
        const val PREF_REQUIRE_AUTH = "require_auth"
        const val PREF_INACTIVITY_TIMEOUT = "inactivity_timeout"
        const val PREF_LOCK_NOW = "lock_now"
        const val PREF_VERBOSE_RCLONE_LOGS = "verbose_rclone_logs"

        // Main UI actions only
        const val PREF_INHIBIT_BATTERY_OPT = "inhibit_battery_opt"
        const val PREF_MISSING_NOTIFICATIONS = "missing_notifications"
        const val PREF_ADD_REMOTE = "add_remote"
        const val PREF_EDIT_REMOTE_PREFIX = "edit_remote_"
        const val PREF_IMPORT_CONFIGURATION = "import_configuration"
        const val PREF_EXPORT_CONFIGURATION = "export_configuration"
        const val PREF_SAVE_LOGS = "save_logs"
        const val PREF_VERSION = "version"

        // Edit remote UI actions
        const val PREF_OPEN_REMOTE = "open_remote"
        const val PREF_CONFIGURE_REMOTE = "configure_remote"
        const val PREF_RENAME_REMOTE = "rename_remote"
        const val PREF_DUPLICATE_REMOTE = "duplicate_remote"
        const val PREF_DELETE_REMOTE = "delete_remote"
        const val PREF_ALLOW_EXTERNAL_ACCESS = "allow_external_access"
        const val PREF_ALLOW_LOCKED_ACCESS = "allow_locked_access"
        const val PREF_DYNAMIC_SHORTCUT = "dynamic_shortcut"
        const val PREF_VFS_CACHING = "vfs_caching"
        const val PREF_REPORT_USAGE = "report_usage"

        // Not associated with a UI preference
        const val PREF_DEBUG_MODE = "debug_mode"
        private const val PREF_NEXT_NOTIFICATION_ID = "next_notification_id"

        // This needs to be large enough to account for activity transitions, where the lock state
        // will briefly become inactive. We also need to make sure it's high enough that the user
        // can't lock themselves out.
        const val MIN_INACTIVITY_TIMEOUT = 15
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun registerListener(listener: OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var isDebugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    /** Whether to automatically add a file extension when creating new files. */
    var addFileExtension: Boolean
        get() = prefs.getBoolean(PREF_ADD_FILE_EXTENSION, true)
        set(enabled) = prefs.edit { putBoolean(PREF_ADD_FILE_EXTENSION, enabled) }

    /** Whether to falsely advertise all roots as local storage. */
    var pretendLocal: Boolean
        get() = prefs.getBoolean(PREF_PRETEND_LOCAL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_PRETEND_LOCAL, enabled) }

    /** Whether to show dialogs at the bottom of the screen. */
    var dialogsAtBottom: Boolean
        get() = prefs.getBoolean(PREF_DIALOGS_AT_BOTTOM, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DIALOGS_AT_BOTTOM, enabled) }

    /** Whether biometric or device credential auth is required. */
    var requireAuth: Boolean
        get() = prefs.getBoolean(PREF_REQUIRE_AUTH, false)
        set(enabled) = prefs.edit { putBoolean(PREF_REQUIRE_AUTH, enabled) }

    /** Inactivity timeout (in seconds). */
    var inactivityTimeout: Int
        get() = max(prefs.getInt(PREF_INACTIVITY_TIMEOUT, 60), MIN_INACTIVITY_TIMEOUT)
        set(seconds) = prefs.edit {
            putInt(PREF_INACTIVITY_TIMEOUT, max(seconds, MIN_INACTIVITY_TIMEOUT))
        }

    /** Whether to allow app data backups. */
    var allowBackup: Boolean
        get() = prefs.getBoolean(PREF_ALLOW_BACKUP, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ALLOW_BACKUP, enabled) }

    /** Whether to enable verbose rclone logging. */
    var verboseRcloneLogs: Boolean
        get() = prefs.getBoolean(PREF_VERBOSE_RCLONE_LOGS, false)
        set(enabled) = prefs.edit { putBoolean(PREF_VERBOSE_RCLONE_LOGS, enabled) }

    /** Get a unique notification ID that increments on every call. */
    val nextNotificationId: Int
        get() = synchronized(context.applicationContext) {
            val nextId = prefs.getInt(PREF_NEXT_NOTIFICATION_ID, 0)
            prefs.edit { putInt(PREF_NEXT_NOTIFICATION_ID, nextId + 1) }
            nextId
        }
}
