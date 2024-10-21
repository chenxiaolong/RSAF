/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView

abstract class PreferenceBaseFragment : PreferenceFragmentCompat() {
    companion object {
        private const val INACTIVE_TIMEOUT_NS = 60_000_000_000L

        // These are intentionally global to ensure that the prompt does not appear when navigating
        // within the app.
        private var bioAuthenticated = false
        private var lastPause = 0L
    }

    abstract val requestTag: String

    protected lateinit var prefs: Preferences
    private lateinit var bioPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        val activity = requireActivity()

        prefs = Preferences(activity)

        bioPrompt = BiometricPrompt(
            this,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(
                        activity,
                        getString(R.string.biometric_error, errString),
                        Toast.LENGTH_LONG,
                    ).show()
                    activity.finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    bioAuthenticated = true
                    refreshGlobalVisibility()
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(
                        activity,
                        R.string.biometric_failure,
                        Toast.LENGTH_LONG,
                    ).show()
                    activity.finish()
                }
            },
        )

        super.onCreate(savedInstanceState)
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val view = super.onCreateRecyclerView(inflater, parent, savedInstanceState)

        view.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )

            // This is a little bit ugly in landscape mode because the divider lines for categories
            // extend into the inset area. However, it's worth applying the left/right padding here
            // anyway because it allows the inset area to be used for scrolling instead of just
            // being a useless dead zone.
            v.updatePadding(
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right,
            )

            WindowInsetsCompat.CONSUMED
        }

        return view
    }

    override fun onResume() {
        super.onResume()

        if (bioAuthenticated && (System.nanoTime() - lastPause) >= INACTIVE_TIMEOUT_NS) {
            bioAuthenticated = false
        }

        if (!bioAuthenticated) {
            if (!prefs.requireAuth) {
                bioAuthenticated = true
            } else {
                startBiometricAuth()
            }
        }

        refreshGlobalVisibility()
    }

    override fun onPause() {
        super.onPause()

        lastPause = System.nanoTime()
    }

    private fun startBiometricAuth() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .setTitle(getString(R.string.biometric_title))
            .build()

        bioPrompt.authenticate(promptInfo)
    }

    private fun refreshGlobalVisibility() {
        view?.visibility = if (bioAuthenticated) {
            View.VISIBLE
        } else {
            // Using View.GONE causes noticeable scrolling jank due to relayout.
            View.INVISIBLE
        }
    }
}
