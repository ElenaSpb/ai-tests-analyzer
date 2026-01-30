package com.semantic.coverage.dto

data class TestChunk(
    val id: String,
    val filePath: String,
    val testName: String,
    val content: String,
    val embedding: FloatArray? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class MatchResult(
    val requirement: BusinessRequirement,
    val testChunk: TestChunk,
    val similarityScore: Float,
    val evidence: String = "",
    val confidence: ConfidenceLevel
)

enum class ConfidenceLevel {
    HIGH,    // similarity > 0.7 и четкая семантическая связь
    MEDIUM,  // similarity 0.4-0.7 или косвенная связь
    LOW      // similarity < 0.4 или слабая связь
}