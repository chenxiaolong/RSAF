/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.nio.ByteBuffer

/**
 * Store the rclone password in a [android.content.SharedPreferences] file with keys and values
 * encrypted by a hardware-backed key.
 *
 * The storage format is identical to what the deprecated androidx security-crypto library used to
 * ensure backwards compatibility.
 */
class RclonePasswordStore(context: Context) {
    companion object {
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        private const val KEY_KEYSET_ALIAS =
            "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        private const val VALUE_KEYSET_ALIAS =
            "__androidx_security_crypto_encrypted_prefs_value_keyset__"

        private const val KEY_RCLONE_CONFIG_PASS = "rclone_config_pass"

        private const val PREF_FILE_NAME = "rclone_config"

        init {
            DeterministicAeadConfig.register()
            AeadConfig.register()
        }
    }

    private val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    // These will implicitly (and in a thread-safe way) generate the master key if it does not
    // already exist.
    private val keyDeterministicAead = AndroidKeysetManager.Builder()
        .withKeyTemplate(KeyTemplates.get("AES256_SIV"))
        .withSharedPref(context, KEY_KEYSET_ALIAS, PREF_FILE_NAME)
        .withMasterKeyUri(AndroidKeystoreKmsClient.PREFIX + MASTER_KEY_ALIAS)
        .build()
        .keysetHandle
        .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
    private val valueAead = AndroidKeysetManager.Builder()
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withSharedPref(context, VALUE_KEYSET_ALIAS, PREF_FILE_NAME)
        .withMasterKeyUri(AndroidKeystoreKmsClient.PREFIX + MASTER_KEY_ALIAS)
        .build()
        .keysetHandle
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    private data class EncryptedKey(val data: String)

    private data class EncryptedValue(val data: String)

    @Suppress("SameParameterValue")
    private fun encryptKey(plainKey: String): EncryptedKey {
        val encryptedKeyRaw = keyDeterministicAead.encryptDeterministically(
            plainKey.toByteArray(),
            PREF_FILE_NAME.toByteArray(),
        )
        return EncryptedKey(Base64.encodeToString(encryptedKeyRaw, Base64.NO_WRAP))
    }

    @Suppress("unused")
    private fun decryptKey(encryptedKey: EncryptedKey): String {
        val plainKeyRaw = keyDeterministicAead.decryptDeterministically(
            Base64.decode(encryptedKey.data, Base64.DEFAULT),
            PREF_FILE_NAME.toByteArray(),
        )
        return plainKeyRaw.toString(Charsets.UTF_8)
    }

    private fun encryptStringValue(encryptedKey: EncryptedKey, plainValue: String): EncryptedValue {
        val plainValueRaw = plainValue.toByteArray()
        val buffer = ByteBuffer.allocate(2 * Int.SIZE_BYTES + plainValueRaw.size).apply {
            putInt(0)
            putInt(plainValueRaw.size)
            put(plainValueRaw)
        }
        val encryptedWrapper = valueAead.encrypt(buffer.array(), encryptedKey.data.toByteArray())
        return EncryptedValue(Base64.encodeToString(encryptedWrapper, Base64.NO_WRAP))
    }

    private fun decryptStringValue(encryptedKey: EncryptedKey, encryptedValue: EncryptedValue): String {
        val plainWrapper = valueAead.decrypt(
            Base64.decode(encryptedValue.data, Base64.DEFAULT),
            encryptedKey.data.toByteArray(),
        )
        val buffer = ByteBuffer.wrap(plainWrapper)

        val typeId = buffer.getInt()
        if (typeId != 0) {
            throw IllegalStateException("Encrypted value type is not a string: $typeId")
        }

        val size = buffer.getInt()
        if (size != buffer.remaining()) {
            throw IllegalStateException("Encrypted value is truncated: $size != ${buffer.remaining()}")
        }

        return Charsets.UTF_8.decode(buffer.slice()).toString()
    }

    var password: String?
        get() {
            val encryptedKey = encryptKey(KEY_RCLONE_CONFIG_PASS)
            return prefs.getString(encryptedKey.data, null)?.let {
                decryptStringValue(encryptedKey, EncryptedValue(it))
            }
        }
        set(value) {
            prefs.edit {
                val encryptedKey = encryptKey(KEY_RCLONE_CONFIG_PASS)
                if (value == null) {
                    remove(encryptedKey.data)
                } else {
                    putString(encryptedKey.data, encryptStringValue(encryptedKey, value).data)
                }
            }
        }
}
