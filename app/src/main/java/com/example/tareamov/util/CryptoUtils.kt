package com.example.tareamov.util

import java.security.MessageDigest
import kotlin.text.Charsets // Added import

object CryptoUtils {
    fun sha256(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8) // Changed to use UTF-8
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}