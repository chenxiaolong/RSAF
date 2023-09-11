package com.chiller3.rsaf

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        val transaction = supportFragmentManager.beginTransaction()

        // https://issuetracker.google.com/issues/181805603
        val bioFragment = supportFragmentManager
            .findFragmentByTag("androidx.biometric.BiometricFragment")
        if (bioFragment != null) {
            transaction.remove(bioFragment)
        }

        if (savedInstanceState == null) {
            transaction.replace(R.id.settings, SettingsFragment())
        }

        transaction.commit()

        setSupportActionBar(findViewById(R.id.toolbar))
    }
}