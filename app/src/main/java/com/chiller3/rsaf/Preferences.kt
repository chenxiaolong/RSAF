package com.chiller3.rsaf

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Preferences(context: Context) {
    companion object {
        const val CATEGORY_CONFIGURATION = "configuration"
        const val CATEGORY_DEBUG = "debug"
        const val CATEGORY_REMOTES = "remotes"

        const val PREF_ADD_FILE_EXTENSION = "add_file_extension"
        const val PREF_ALLOW_BACKUP = "allow_backup"
        const val PREF_DIALOGS_AT_BOTTOM = "dialogs_at_bottom"
        const val PREF_LOCAL_STORAGE_ACCESS = "local_storage_access"
        const val PREF_POSIX_LIKE_SEMANTICS = "posix_like_semantics"
        const val PREF_PRETEND_LOCAL = "pretend_local"
        const val PREF_REQUIRE_AUTH = "require_auth"
        const val PREF_VERBOSE_RCLONE_LOGS = "verbose_rclone_logs"

        // UI actions only
        const val PREF_ADD_REMOTE = "add_remote"
        const val PREF_EDIT_REMOTE_PREFIX = "edit_remote_"
        const val PREF_IMPORT_CONFIGURATION = "import_configuration"
        const val PREF_EXPORT_CONFIGURATION = "export_configuration"
        const val PREF_SAVE_LOGS = "save_logs"
        const val PREF_VERSION = "version"

        // Not associated with a UI preference
        const val PREF_DEBUG_MODE = "debug_mode"
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

    /** Whether to use POSIX-like semantics instead of Android-like semantics. */
    var posixLikeSemantics: Boolean
        get() = prefs.getBoolean(PREF_POSIX_LIKE_SEMANTICS, false)
        set(enabled) = prefs.edit { putBoolean(PREF_POSIX_LIKE_SEMANTICS, enabled) }

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

    /** Whether to allow app data backups. */
    var allowBackup: Boolean
        get() = prefs.getBoolean(PREF_ALLOW_BACKUP, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ALLOW_BACKUP, enabled) }

    /** Whether to enable verbose rclone logging. */
    var verboseRcloneLogs: Boolean
        get() = prefs.getBoolean(PREF_VERBOSE_RCLONE_LOGS, false)
        set(enabled) = prefs.edit { putBoolean(PREF_VERBOSE_RCLONE_LOGS, enabled) }
}