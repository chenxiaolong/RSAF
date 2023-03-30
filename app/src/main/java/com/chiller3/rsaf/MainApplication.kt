package com.chiller3.rsaf

import android.app.Application
import android.app.backup.BackupManager
import android.content.SharedPreferences
import android.util.Log
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.google.android.material.color.DynamicColors
import java.io.File

class MainApplication : Application(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private val TAG = MainApplication::class.java.simpleName
    }

    private lateinit var prefs: Preferences
    private lateinit var backupManager: BackupManager

    override fun onCreate() {
        super.onCreate()

        val oldCrashHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val logcatFile = File(getExternalFilesDir(null), Logcat.FILENAME_CRASH)
                Log.e(TAG, "Saving logcat to $logcatFile due to uncaught exception in $t", e)
                Logcat.dump(logcatFile)
            } finally {
                oldCrashHandler?.uncaughtException(t, e)
            }
        }

        prefs = Preferences(this)
        prefs.registerListener(this)

        backupManager = BackupManager(this)

        initRclone()

        Logcat.init(this)

        // Enable Material You colors
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    private fun initRclone() {
        Rcbridge.rbInit(File(cacheDir, "rclone").toString())
        RcloneConfig.init(this)
        updateRcloneVerbosity()
    }

    private fun updateRcloneVerbosity() {
        val verbosity = if (prefs.isDebugMode) {
            if (prefs.verboseRcloneLogs) {
                2L
            } else {
                1L
            }
        } else {
            0L
        }
        Rcbridge.rbSetLogVerbosity(verbosity)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Preferences.PREF_DEBUG_MODE, Preferences.PREF_VERBOSE_RCLONE_LOGS ->
                updateRcloneVerbosity()
        }

        Log.i(TAG, "$key preference was changed; notifying backup manager")
        backupManager.dataChanged()
    }
}