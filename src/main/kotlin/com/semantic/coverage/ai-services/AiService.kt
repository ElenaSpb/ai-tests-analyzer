package com.semantic.coverage.`ai-services`

// Интерфейс для AI сервисов
interface AiService {
    fun analyze(prompt: String): String
    fun analyzeTestCoverage(requirement: String, testCode: String): String
}