package com.semantic.coverage.analyze

import com.semantic.coverage.aiServices.AiService
import com.semantic.coverage.aiServices.MistralService
import com.semantic.coverage.dto.*

/**
 * Сервис для глубокого AI анализа покрытия
 */
class DeepCoverageAnalyzer(
    private val aiService: AiService
) {

    /**
     * Выполняет глубокий анализ покрытия требования тестами
     */
    fun performDeepAnalysis(
        requirement: BusinessRequirement,
        relatedTests: List<TestChunk>,
        context: CoverageContext? = null
    ): DeepCoverageAnalysis {

        val requirementText = formatRequirementForAnalysis(requirement)
        val testCode = formatTestsForAnalysis(relatedTests)

        // Используем специализированный метод
        val aiAnalysis = aiService.analyzeTestCoverage(requirementText, testCode)

        // Дополнительный анализ с контекстом (если доступно)
        val extendedAnalysis = if (context != null && aiService is MistralService) {
            // Используем расширенный метод Mistral
            aiService.analyzeCoverageWithContext(
                requirementText,
                testCode,
                context.toMap()
            )
        } else {
            aiAnalysis
        }

        return DeepCoverageAnalysis(
            requirement = requirement,
            aiResponse = extendedAnalysis,
            analyzedTests = relatedTests,
            timestamp = java.time.LocalDateTime.now()
        )
    }

    private fun formatRequirementForAnalysis(requirement: BusinessRequirement): String {
        return buildString {
            append("# Требование: ${requirement.title}\n")
            append("## ID: ${requirement.id}\n")
            append("## Описание:\n${requirement.description}\n")

            if (requirement.acceptanceCriteria.isNotEmpty()) {
                append("## Критерии приемки:\n")
                requirement.acceptanceCriteria.forEachIndexed { i, c ->
                    append("${i + 1}. $c\n")
                }
            }

            if (requirement.tags.isNotEmpty()) {
                append("## Теги: ${requirement.tags.joinToString(", ")}\n")
            }

            append("## Категория: ${requirement.category}\n")
            append("## Приоритет: ${requirement.priority}\n")
        }
    }

    private fun formatTestsForAnalysis(tests: List<TestChunk>): String {
        return tests.take(5).joinToString("\n\n${"=".repeat(80)}\n\n") { test ->
            """
            // Файл: ${test.filePath}
            // Тест: ${test.testName}
            ${if (test.metadata.isNotEmpty()) "// Метаданные: ${test.metadata}\n" else ""}
            ${test.content.take(2000)}
            """.trimIndent()
        }
    }
}

data class DeepCoverageAnalysis(
    val requirement: BusinessRequirement,
    val aiResponse: String,
    val analyzedTests: List<TestChunk>,
    val timestamp: java.time.LocalDateTime,
    val confidenceScore: Double = 0.0
)

data class CoverageContext(
    val projectStack: String = "",
    val architecture: String = "",
    val qualityStandards: String = "",
    val additionalInfo: Map<String, String> = emptyMap()
) {
    fun toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (projectStack.isNotBlank()) map["Технологический стек"] = projectStack
        if (architecture.isNotBlank()) map["Архитектура"] = architecture
        if (qualityStandards.isNotBlank()) map["Стандарты качества"] = qualityStandards
        map.putAll(additionalInfo)
        return map
    }
}