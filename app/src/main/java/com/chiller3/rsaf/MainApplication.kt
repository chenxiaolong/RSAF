package com.chiller3.rsaf

import android.app.Application
import android.app.backup.BackupManager
import android.content.SharedPreferences
import android.util.Log
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

        Logcat.init(this)

        // Enable Material You colors
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.i(TAG, "$key preference was changed; notifying backup manager")
        backupManager.dataChanged()
    }
}