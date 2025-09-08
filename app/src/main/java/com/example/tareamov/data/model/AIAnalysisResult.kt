package com.example.tareamov.data.model

data class AIAnalysisResult(
    val success: Boolean,
    val analysis: String = "",
    val error: String? = null,
    val confidence: Float = 0.0f,
    val model: String = "",
    val response: String = "",
    val context: String? = null
)
