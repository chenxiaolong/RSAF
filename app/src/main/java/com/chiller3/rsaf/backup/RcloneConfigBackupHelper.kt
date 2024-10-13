/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.backup

import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.app.backup.BackupHelper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.chiller3.rsaf.rclone.RcloneConfig
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

class RcloneConfigBackupHelper : BackupHelper {
    companion object {
        private val TAG = RcloneConfigBackupHelper::class.java.simpleName

        private const val HASH_SIZE = 512 / 8

        private const val ENTITY_KEY = "rclone_config"

        private fun readHashFromState(oldState: ParcelFileDescriptor): ByteArray? {
            val buf = ByteArray(HASH_SIZE)
            val n = Os.read(oldState.fileDescriptor, buf, 0, buf.size)
            if (n != buf.size) {
                return null
            }

            return buf
        }

        private fun writeHashToState(newState: ParcelFileDescriptor, hash: ByteArray) {
            if (hash.size != HASH_SIZE) {
                throw IllegalArgumentException("Invalid hash size: ${hash.size}")
            }

            val n = Os.write(newState.fileDescriptor, hash, 0, hash.size)
            if (n != hash.size) {
                throw EOFException("Reached EOF before hash was fully written")
            }
        }

        private fun hashPayload(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(data)
    }

    override fun performBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        try {
            val payloadStream = ByteArrayOutputStream()
            RcloneConfig.exportConfiguration(payloadStream, "")

            val payload = payloadStream.toByteArray()
            val oldPayloadHash = oldState?.let { readHashFromState(it) }
            val newPayloadHash = hashPayload(payload)

            if (!oldPayloadHash.contentEquals(newPayloadHash)) {
                data.writeEntityHeader(ENTITY_KEY, payload.size)
                data.writeEntityData(payload, payload.size)
                Log.i(TAG, "Wrote new ${payload.size} byte payload")
            } else {
                Log.i(TAG, "Skipping backup because old hash matches")
            }

            writeHashToState(newState, newPayloadHash)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to back up rclone configuration", e)
        }
    }

    override fun restoreEntity(data: BackupDataInputStream) {
        if (data.key != ENTITY_KEY) {
            Log.w(TAG, "Unexpected entity key: ${data.key}")
            return
        }

        Log.i(TAG, "Restoring ${data.size()} byte payload")

        try {
            RcloneConfig.importConfiguration(EntityInputStream(data), "")
            Log.i(TAG, "Successfully restored rclone configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore rclone configuration", e)
        }
    }

    override fun writeNewStateDescription(newState: ParcelFileDescriptor?) {
    }

    private class EntityInputStream(private val inner: BackupDataInputStream) : InputStream() {
        private var remain = inner.size()

        override fun read(): Int {
            if (remain == 0) {
                return -1
            }

            return inner.read().also {
                remain -= 1
            }
        }

        override fun read(b: ByteArray, offset: Int, size: Int): Int {
            val toRead = Integer.min(remain, size)
            if (toRead == 0) {
                return -1
            }

            return inner.read(b, offset, size).also {
                remain -= it
            }
        }

        override fun read(b: ByteArray): Int {
            return read(b, 0, b.size)
        }
    }
}