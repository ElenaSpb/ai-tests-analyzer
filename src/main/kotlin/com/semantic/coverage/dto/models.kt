package com.semantic.coverage.dto

data class TestChunk(
    val id: String,
    val filePath: String,
    val testName: String,
    val content: String,
    val embedding: FloatArray? = null,
    val metadata: Map<String, String> = emptyMap()
)

//data class Requirement(
//    val id: String,
//    val title: String,
//    val description: String,
//    val category: String = "business",
//    val embedding: FloatArray? = null
//)

data class MatchResult(
    val requirement: Requirement,
    val testChunk: TestChunk,
    val similarityScore: Float,
    val evidence: String = "",
    val confidence: ConfidenceLevel
)

//data class CoverageReport(
//    val requirement: Requirement,
//    val matches: List<MatchResult>,
//    val coverageScore: Float,
//    val confidence: ConfidenceLevel,
//    val gaps: List<String> = emptyList(),
//    val timestamp: String = java.time.LocalDateTime.now().toString()
//)

enum class ConfidenceLevel {
    HIGH,    // similarity > 0.7 и четкая семантическая связь
    MEDIUM,  // similarity 0.4-0.7 или косвенная связь
    LOW      // similarity < 0.4 или слабая связь
}