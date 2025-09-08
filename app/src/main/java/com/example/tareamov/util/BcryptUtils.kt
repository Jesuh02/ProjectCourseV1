package com.example.tareamov.util

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Utility class for BCrypt password hashing operations
 */
object BcryptUtils {
    
    /**
     * Hash a password using BCrypt with cost factor 12
     * @param password The plain text password to hash
     * @return The hashed password string
     */
    fun hash(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }
    
    /**
     * Verify a password against a BCrypt hash
     * @param password The plain text password to verify
     * @param hashedPassword The stored BCrypt hash
     * @return true if the password matches the hash, false otherwise
     */
    fun verify(password: String, hashedPassword: String): Boolean {
        return try {
            BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if a string is a valid BCrypt hash
     * @param hash The string to check
     * @return true if it's a valid BCrypt hash format, false otherwise
     */
    fun isValidHash(hash: String): Boolean {
        return try {
            // BCrypt hashes start with $2a$, $2b$, $2x$, or $2y$ and are exactly 60 characters long
            hash.matches(Regex("^\\$2[abxy]\\$\\d{2}\\$.{53}$"))
        } catch (e: Exception) {
            false
        }
    }
}
