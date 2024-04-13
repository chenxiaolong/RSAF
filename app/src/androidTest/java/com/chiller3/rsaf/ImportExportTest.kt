package com.chiller3.rsaf

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ImportExportTest {
    companion object {
        private fun parseConfig(contents: String): Map<String, Map<String, String>> {
            val result = mutableMapOf<String, MutableMap<String, String>>()
            var section: String? = null

            for (line in contents.splitToSequence('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) {
                    continue
                }

                if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                    section = trimmed.substring(1, trimmed.length - 1)
                    result[section] = mutableMapOf()
                } else if (section == null) {
                    throw IllegalStateException("Key/value pair before section: $trimmed")
                } else {
                    val pieces = trimmed.split('=', limit = 2)
                    if (pieces.size != 2) {
                        throw IllegalStateException("Invalid key/value pair: $trimmed")
                    }

                    result[section]!![pieces[0].trim()] = pieces[1].trim()
                }
            }

            return result
        }

        private fun isEncryptedConfig(contents: String): Boolean {
            for (line in contents.splitToSequence('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) {
                    continue
                }

                return trimmed == "RCLONE_ENCRYPT_V0:"
            }

            return false
        }
    }

    private lateinit var remote: String
    private lateinit var target: String

    @Before
    fun createTempRemote() {
        remote = "testing." + RandomUtils.generatePassword(16, RandomUtils.ASCII_ALPHANUMERIC)
        target = "/" + RandomUtils.generatePassword(128, RandomUtils.ASCII_ALPHANUMERIC)

        val iq = RcloneRpc.InteractiveConfiguration(remote)
        while (true) {
            val (_, option) = iq.question ?: break

            when (option.name) {
                "type" -> iq.submit("alias")
                "remote" -> iq.submit(target)
                "config_fs_advanced" -> iq.submit("false")
                else -> throw IllegalStateException("Unexpected question: ${option.name}")
            }
        }

        assert(RcloneRpc.remoteNames.contains(remote))
    }

    @After
    fun deleteTempRemote() {
        RcloneRpc.deleteRemote(remote)
    }

    @Test
    fun testPlainText() {
        val outputStream = ByteArrayOutputStream()
        RcloneConfig.exportConfiguration(outputStream, "")

        val config = parseConfig(outputStream.toString(Charsets.UTF_8))
        assertTrue(config.containsKey(remote))
        assertEquals("alias", config[remote]!!["type"])
        assertEquals(target, config[remote]!!["remote"])

        RcloneRpc.deleteRemote(remote)

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        RcloneConfig.importConfiguration(inputStream, "")

        assertTrue(RcloneRpc.remoteNames.contains(remote))
    }

    @Test
    fun testEncrypted() {
        val outputStream = ByteArrayOutputStream()
        RcloneConfig.exportConfiguration(outputStream, "test")

        val rawConfig = outputStream.toString(Charsets.UTF_8)
        assertTrue(isEncryptedConfig(rawConfig))

        RcloneRpc.deleteRemote(remote)

        assertThrows(RcloneConfig.BadPasswordException::class.java) {
            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            RcloneConfig.importConfiguration(inputStream, "wrong")
        }
        assertFalse(RcloneRpc.remoteNames.contains(remote))

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        RcloneConfig.importConfiguration(inputStream, "test")
        assertTrue(RcloneRpc.remoteNames.contains(remote))
    }
}