package com.chiller3.rsaf

import java.security.SecureRandom

object RandomUtils {
    @Suppress("MemberVisibilityCanBePrivate")
    val ASCII_PRINTABLE = (32..126).map { it }
    val ASCII_ALPHANUMERIC =
        (48..57).map { it } +
        (65..90).map { it } +
        (97..122).map { it }

    fun generatePassword(size: Int, alphabet: List<Int> = ASCII_PRINTABLE): String {
        val random = SecureRandom.getInstanceStrong()
        return buildString {
            random.ints(size.toLong(), 0, alphabet.size).forEach {
                appendCodePoint(alphabet[it])
            }
        }
    }
}