/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Companion.biometricRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultLauncher
import androidx.biometric.BiometricPrompt
import androidx.biometric.compose.rememberAuthenticationLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

abstract class BaseActivity : ComponentActivity() {
    companion object {
        private fun supportsModernDeviceCredential() =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    private val tag = javaClass.simpleName

    private lateinit var prefs: Preferences
    private lateinit var activityManager: ActivityManager
    private var isCoveredBySafeActivity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        prefs = Preferences(this)
        activityManager = getSystemService(ActivityManager::class.java)

        setContent {
            var startedOnce by rememberSaveable { mutableStateOf(false) }

            val modernLauncher = rememberAuthenticationLauncher { authResult ->
                startedOnce = false

                when (authResult) {
                    is AuthenticationResult.Success -> onAuthenticationSucceeded()
                    is AuthenticationResult.Error -> {
                        // Ignore cancellation due to eg. orientation change.
                        if (authResult.errorCode != BiometricPrompt.ERROR_CANCELED) {
                            onAuthenticationError(authResult.errorCode, authResult.errString)
                        }
                    }
                    is AuthenticationResult.CustomFallbackSelected ->
                        throw IllegalStateException("Custom fallback not used")
                }
            }

            val legacyLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                startedOnce = false

                if (it.resultCode == RESULT_OK) {
                    onAuthenticationSucceeded()
                } else {
                    // We can't know the reason.
                    onAuthenticationError(
                        BiometricPrompt.ERROR_USER_CANCELED,
                        getString(R.string.biometric_error_cancelled),
                    )
                }
            }

            LifecycleResumeEffect(Unit) {
                Log.d(tag, "onResume()")
                AppLock.onAppResume()

                if (AppLock.isLocked && !startedOnce) {
                    startedOnce = true
                    startAuth(modernLauncher, legacyLauncher)
                }

                refreshTaskState()
                refreshGlobalVisibility()

                onPauseOrDispose {
                    Log.d(tag, "onPause()")
                    AppLock.onAppPause()
                }
            }

            ActivityContent()
        }
    }

    @Composable
    abstract fun ActivityContent()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(tag, "onWindowFocusChanged($hasFocus)")

        refreshTaskState()

        val secure = prefs.requireAuth && !hasFocus && !isCoveredBySafeActivity
        Log.d(tag, "Updating window secure flag: $secure")

        // We only want the top-level activity to handle FLAG_SECURE to avoid flicker in screen
        // recordings and scrcpy.
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // We want the activity to be visible for predictive back gestures as long as the top-level
    // activity in the task is our own.
    private fun canViewAndInteract() = !AppLock.isLocked || isCoveredBySafeActivity

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        val canInteract = canViewAndInteract()
        Log.d(tag, "Updating focusable/touchable state: $canInteract")

        // This trick is from Signal to drop all input events going to the window.
        val ignoreInput = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        params?.let {
            it.flags = if (canInteract) {
                it.flags and ignoreInput.inv()
            } else {
                it.flags or ignoreInput
            }
        }

        super.onWindowAttributesChanged(params)
    }

    private fun startAuth(
        modernLauncher: AuthenticationResultLauncher,
        legacyLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    ) {
        if (supportsModernDeviceCredential()) {
            startBiometricAuth(modernLauncher)
        } else {
            startLegacyDeviceCredentialAuth(legacyLauncher)
        }
    }

    private fun startBiometricAuth(launcher: AuthenticationResultLauncher) {
        Log.d(tag, "Starting biometric authentication")

        launcher.launch(
            biometricRequest(
                title = getString(R.string.biometric_title),
                AuthenticationRequest.Biometric.Fallback.DeviceCredential,
            ) {
                setMinStrength(AuthenticationRequest.Biometric.Strength.Class3())
            }
        )
    }

    private fun startLegacyDeviceCredentialAuth(
        launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    ) {
        Log.d(tag, "Starting legacy device credential authentication")

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
            getString(R.string.biometric_title),
            "",
        )

        if (intent != null) {
            launcher.launch(intent)
        } else {
            onAuthenticationError(
                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                getString(R.string.biometric_ignore),
            )
        }
    }

    fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        Log.d(tag, "Authentication error: $errorCode: $errString")

        if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS
                || errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
            Log.w(tag, "No biometrics or device credential; allowing access")

            Toast.makeText(this, R.string.biometric_ignore, Toast.LENGTH_LONG).show()

            onAuthenticationSucceeded()
            return
        }

        Toast.makeText(
            this,
            getString(R.string.biometric_error, errString),
            Toast.LENGTH_LONG,
        ).show()

        finish()
    }

    private fun onAuthenticationSucceeded() {
        Log.d(tag, "Authentication succeeded")

        AppLock.onAuthSuccess()

        refreshGlobalVisibility()
    }

    private fun refreshTaskState() {
        // This is an awful hack, but we need it to be able to only apply the view hiding in the
        // topmost activity to ensure that predictive back gestures still work.
        val taskId = taskId

        val task = activityManager.appTasks.find {
            taskId == if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.taskInfo?.taskId
            } else {
                @Suppress("DEPRECATION")
                it.taskInfo?.id
            }
        }

        val topActivity = task?.taskInfo?.topActivity

        isCoveredBySafeActivity = topActivity != null
                && topActivity != componentName
                && topActivity.packageName == packageName

        Log.d(tag, "Top-level activity in stack is: $topActivity")
        Log.d(tag, "Covered by safe activity: $isCoveredBySafeActivity")
    }

    private fun refreshGlobalVisibility() {
        window?.let { window ->
            val visible = canViewAndInteract()
            Log.d(tag, "Updating view state: $visible")

            val contentView = window.decorView.findViewById<View>(android.R.id.content)
            contentView.visibility = if (visible) {
                View.VISIBLE
            } else {
                // Using View.GONE causes noticeable scrolling jank due to relayout.
                View.INVISIBLE
            }

            onWindowAttributesChanged(window.attributes)
        }
    }
}
