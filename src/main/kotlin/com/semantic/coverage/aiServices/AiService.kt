package com.semantic.coverage.aiServices

// Интерфейс для AI сервисов
interface AiService {
    fun analyze(prompt: String): String
    fun analyzeTestCoverage(requirement: String, testCode: String): String
}