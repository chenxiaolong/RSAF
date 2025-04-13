/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.app.backup.BackupManager
import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.chiller3.rsaf.RandomUtils
import com.chiller3.rsaf.binding.rcbridge.RbError
import com.chiller3.rsaf.binding.rcbridge.Rcbridge
import com.chiller3.rsaf.extension.toException
import com.chiller3.rsaf.withTempFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

object RcloneConfig {
    private val TAG = RcloneConfig::class.java.simpleName

    const val FILENAME = "rclone.conf"
    const val MIMETYPE = "text/plain"

    private const val ERROR_BAD_PASSWORD = "not allowed to ask for password"

    // We only need this for opening file descriptors and shared preferences. Configuration changes
    // aren't relevant here.
    private lateinit var applicationContext: Context
    private lateinit var backupManager: BackupManager
    private lateinit var appConfigFile: File
    private val passwordStore by lazy {
        RclonePasswordStore(applicationContext)
    }

    /**
     * Rclone's configuration system is all global state. Anything that touches the configuration
     * must do so with the mutex locked.
     */
    private val globalStateLock = Object()

    /** Initialize global config state and load config file. */
    fun init(context: Context) {
        if (this::applicationContext.isInitialized) {
            throw IllegalStateException("Already initialized")
        }

        applicationContext = context.applicationContext
        backupManager = BackupManager(applicationContext)
        appConfigFile = File(context.filesDir, FILENAME)

        synchronized(globalStateLock) {
            setDefaultConfigLocked()
            try {
                loadLocked()
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ENOENT) {
                    Log.w(TAG, "Config file does not exist; creating it")
                    saveLocked()
                } else {
                    throw e
                }
            }
        }
    }

    fun notifyConfigChanged() {
        Log.i(TAG, "Config was changed; notifying backup manager")
        backupManager.dataChanged()
    }

    /**
     * Generate or get rclone config encryption password wrapped by the hardware keystore.
     *
     * This makes use of wrapping because rclone's symmetric config encryption can't be done on the
     * hardware keystore itself. If the password has not yet been generated, a new 128-byte random
     * password is generated and stored.
     */
    private val hardwareWrappedPassword: String
        get() {
            synchronized(passwordStore) {
                if (passwordStore.password == null) {
                    passwordStore.password = RandomUtils.generatePassword(128)
                }

                return passwordStore.password!!
            }
        }

    private fun setConfigLocked(path: String, password: String) {
        val error = RbError()

        if (!Rcbridge.rbConfigSetPath(path, error)) {
            throw error.toException("rbConfigSetPath")
        }

        if (password.isEmpty()) {
            Rcbridge.rbConfigClearPassword()
        } else {
            if (!Rcbridge.rbConfigSetPassword(password, error)) {
                throw error.toException("rbConfigSetPassword")
            }
        }
    }

    private fun setDefaultConfigLocked() {
        setConfigLocked(appConfigFile.toString(), hardwareWrappedPassword)
    }

    private fun loadLocked() {
        val error = RbError()

        if (!Rcbridge.rbConfigLoad(error)) {
            if (error.code.toInt() == OsConstants.EIO) {
                // rclone does not have a distinct error type for this, so we're stuck with doing
                // string matching
                if (error.msg.contains(ERROR_BAD_PASSWORD)) {
                    throw BadPasswordException(error.msg)
                }
            }

            throw error.toException("rbConfigLoad")
        }
    }

    private fun saveLocked() {
        val error = RbError()

        if (!Rcbridge.rbConfigSave(error)) {
            throw error.toException("rbConfigSave")
        }

        notifyConfigChanged()
    }

    fun importConfiguration(input: InputStream, password: String) {
        withTempFile(applicationContext, "import.conf") { tempConfig ->
            tempConfig.outputStream().use { out ->
                input.copyTo(out)
            }

            synchronized(globalStateLock) {
                try {
                    setConfigLocked(tempConfig.toString(), password)
                    loadLocked()
                } catch (e: Exception) {
                    // Restore prior configuration if import fails
                    setDefaultConfigLocked()
                    loadLocked()
                    throw e
                }

                // Otherwise, commit new config
                setDefaultConfigLocked()
                saveLocked()
            }
        }
    }

    fun importConfigurationUri(uri: Uri, password: String) {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open for reading: $uri")

        input.use {
            importConfiguration(input, password)
        }
    }

    fun exportConfiguration(output: OutputStream, password: String) {
        withTempFile(applicationContext, "export.conf") { tempConfig ->
            synchronized(globalStateLock) {
                try {
                    setConfigLocked(tempConfig.toString(), password)
                    saveLocked()
                } finally {
                    setDefaultConfigLocked()
                }
            }

            tempConfig.inputStream().use {
                it.copyTo(output)
            }
        }
    }

    fun exportConfigurationUri(uri: Uri, password: String) {
        val output = applicationContext.contentResolver.openOutputStream(uri)
            ?: throw IOException("Failed to open for writing: $uri")

        output.use {
            exportConfiguration(output, password)
        }
    }

    fun checkName(remote: String) {
        val error = RbError()
        if (!Rcbridge.rbConfigCheckName(remote, error)) {
            throw error.toException("rbConfigCheckName")
        }
    }

    fun copyRemote(oldRemote: String, newRemote: String) {
        synchronized(globalStateLock) {
            Rcbridge.rbConfigCopySection(oldRemote, newRemote)
            saveLocked()
        }
    }

    class BadPasswordException(message: String?, cause: Throwable? = null)
        : Exception(message, cause)

    fun revealPassword(obscured: String): String {
        val error = RbError()
        val result = Rcbridge.rbPasswordReveal(obscured, error)
            ?: throw error.toException("rbPasswordReveal")

        return result.plainText
    }
}