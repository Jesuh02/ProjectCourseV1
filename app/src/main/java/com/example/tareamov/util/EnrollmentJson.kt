package com.example.tareamov.util

import com.google.gson.JsonObject

fun JsonObject.getStringOrNull(key: String): String? =
    get(key)
        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun JsonObject.getEnrollmentStatusOrNull(): String? =
    sequenceOf("enrollmentStatus", "enrollment_status", "status")
        .mapNotNull(::getStringOrNull)
        .firstOrNull()
        ?.lowercase()
