package com.semantic.coverage.analyze

import com.semantic.coverage.aiServices.AiService
import com.semantic.coverage.dto.*
import com.semantic.coverage.embedding.EmbeddingService
import java.util.*

class CoverageAiAnalyzer(
    private val embeddingService: EmbeddingService,
    private val aiService: AiService? = null,
    private val useAI: Boolean = false,
    private val similarityThreshold: Float = 0.3f
) {
    fun analyzeCoverage(
        requirements: List<BusinessRequirement>,
        testChunks: List<TestChunk>
    ): List<CoverageReport> {
        println("Начинаем анализ семантического покрытия...")
        println("   Требований: ${requirements.size}")
        println("   Тестовых чанков: ${testChunks.size}")
        println("   Использовать AI: $useAI")

        // 1. Векторизуем требования с учетом всех полей (включая критерии и теги)
        println("📊 Векторизация требований...")
        val requirementsWithEmbeddings = requirements.mapIndexed { index, req ->
            print("\r   Обработано ${index + 1}/${requirements.size} требований")
            req.copy(embedding = embeddingService.getEmbedding(req.getFullTextForEmbedding()))
        }
        println()

        // 2. Векторизуем тесты (если еще не векторизованы)
        println("📊 Векторизация тестов...")
        val testChunksWithEmbeddings = testChunks.mapIndexed { index, chunk ->
            print("\r   Обработано ${index + 1}/${testChunks.size} тестов")
            if (chunk.embedding == null) {
                val enrichedContent = buildString {
                    append(chunk.testName)
                    append("\n")
                    append(chunk.content)
                    if (chunk.metadata.isNotEmpty()) {
                        append("\nМетаданные: ")
                        append(chunk.metadata.values.joinToString(" "))
                    }
                }
                chunk.copy(embedding = embeddingService.getEmbedding(enrichedContent))
            } else {
                chunk
            }
        }
        println()

        // 3. Для каждого требования ищем соответствия
        println("🔍 Поиск соответствий...")
        return requirementsWithEmbeddings.mapIndexed { index, requirement ->
            println("Требование ${index + 1}/${requirements.size}: ${requirement.title}")

            val matches = findMatchesForRequirement(requirement, testChunksWithEmbeddings)
            val coverageScore = calculateCoverageScore(requirement, matches)
            val gaps = identifyGaps(requirement, matches)

            // AI анализ (если включен)
            val aiAnalysis = if (useAI && aiService != null) {
                println("🤖 Запуск расширенного AI анализа...")
                try {
                    analyzeWithAI(requirement, matches, aiService)
                } catch (e: Exception) {
                    println("⚠️  Ошибка AI анализа: ${e.message}")
                    null
                }
            } else null

            val confidence = calculateConfidenceLevel(matches, aiAnalysis)

            CoverageReport(
                requirement = requirement,
                matches = matches,
                coverageScore = coverageScore,
                confidence = confidence,
                gaps = gaps,
                aiAnalysis = aiAnalysis
            )
        }
    }

    private fun findMatchesForRequirement(
        requirement: BusinessRequirement,
        testChunks: List<TestChunk>,
        topK: Int = 10
    ): List<MatchResult> {
        require(requirement.embedding != null) { "Requirement must have embedding" }

        if (testChunks.isEmpty()) return emptyList()

        // Фильтруем тесты с векторами
        val testChunksWithEmbeddings = testChunks.filter { it.embedding != null }

        if (testChunksWithEmbeddings.isEmpty()) return emptyList()

        // Вычисляем семантическое сходство
        val matchResults = testChunksWithEmbeddings.map { chunk ->
            val similarity = embeddingService.cosineSimilarity(
                requirement.embedding,
                chunk.embedding!!
            )

            val confidence = when {
                similarity > 0.7 -> ConfidenceLevel.HIGH
                similarity > 0.4 -> ConfidenceLevel.MEDIUM
                else -> ConfidenceLevel.LOW
            }

            MatchResult(
                requirement = requirement,
                testChunk = chunk,
                similarityScore = similarity,
                evidence = generateEvidence(requirement, chunk, similarity),
                confidence = confidence
            )
        }

        // Фильтруем по порогу и сортируем
        return matchResults
            .filter { it.similarityScore >= similarityThreshold }
            .sortedByDescending { it.similarityScore }
            .take(topK)
    }

    private fun calculateCoverageScore(requirement: BusinessRequirement, matches: List<MatchResult>): Float {
        if (matches.isEmpty()) return 0.0f

        // Базовый расчет на основе сходства
        val highConfidenceMatches = matches.filter { it.confidence == ConfidenceLevel.HIGH }
        val mediumConfidenceMatches = matches.filter { it.confidence == ConfidenceLevel.MEDIUM }

        val weightedMatches = (highConfidenceMatches.size * 1.0f) +
                (mediumConfidenceMatches.size * 0.6f) +
                (matches.size * 0.3f)

        val maxPossible = matches.size * 1.9f
        val weightedScore = if (maxPossible > 0) weightedMatches / maxPossible else 0.0f
        val maxSimilarity = matches.maxOfOrNull { it.similarityScore } ?: 0.0f
        val baseScore = ((weightedScore * 0.6f) + (maxSimilarity * 0.4f)) * 100f

        // Улучшение оценки при наличии критериев приемки и их покрытия
        if (requirement.acceptanceCriteria.isNotEmpty()) {
            val coveredCriteria = requirement.acceptanceCriteria.count { criterion ->
                matches.any { match ->
                    val testText = "${match.testChunk.testName} ${match.testChunk.content}"
                    testText.contains(criterion, ignoreCase = true) ||
                            (match.testChunk.embedding?.let { embedding ->
                                embeddingService.cosineSimilarity(
                                    embeddingService.getEmbedding(criterion),
                                    embedding
                                ) > 0.6f
                            } ?: false)
                }
            }
            val criteriaCoverage = (coveredCriteria.toFloat() / requirement.acceptanceCriteria.size) * 20f // до +20%
            return (baseScore + criteriaCoverage).coerceAtMost(100f)
        }

        return baseScore
    }

    private fun calculateConfidenceLevel(
        matches: List<MatchResult>,
        aiAnalysis: AIAnalysis?
    ): ConfidenceLevel {
        if (matches.isEmpty()) return ConfidenceLevel.LOW

        // Учитываем AI анализ, если есть
        aiAnalysis?.let {
            return when (it.confidence) {
                ConfidenceLevel.HIGH -> if (matches.any { m -> m.confidence == ConfidenceLevel.HIGH })
                    ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM

                ConfidenceLevel.MEDIUM -> ConfidenceLevel.MEDIUM
                ConfidenceLevel.LOW -> ConfidenceLevel.LOW
            }
        }

        // Иначе вычисляем на основе статистики
        val highCount = matches.count { it.confidence == ConfidenceLevel.HIGH }
        val mediumCount = matches.count { it.confidence == ConfidenceLevel.MEDIUM }
        val avgSimilarity = matches.map { it.similarityScore }.average()

        return when {
            highCount >= 2 && avgSimilarity > 0.6 -> ConfidenceLevel.HIGH
            (highCount + mediumCount) >= 2 && avgSimilarity > 0.4 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    private fun identifyGaps(
        requirement: BusinessRequirement,
        matches: List<MatchResult>
    ): List<String> {
        val gaps = mutableListOf<String>()

        // 1. Проверяем наличие соответствий
        if (matches.isEmpty()) {
            gaps.add("Требование не имеет ни одного связанного теста")
            return gaps
        }

        // 2. Анализ покрытия критериев приемки
        if (requirement.acceptanceCriteria.isNotEmpty()) {
            val uncoveredCriteria = requirement.acceptanceCriteria.filter { criterion ->
                matches.none { match ->
                    val testText = "${match.testChunk.testName} ${match.testChunk.content}"
                    testText.contains(criterion, ignoreCase = true) ||
                            (match.testChunk.embedding?.let { embedding ->
                                embeddingService.cosineSimilarity(
                                    embeddingService.getEmbedding(criterion),
                                    embedding
                                ) > 0.55f
                            } ?: false)
                }
            }

            if (uncoveredCriteria.isNotEmpty()) {
                val displayCriteria = uncoveredCriteria.take(3).joinToString(", ")
                gaps.add("Не покрыты критерии приемки: $displayCriteria" +
                        if (uncoveredCriteria.size > 3) " и еще ${uncoveredCriteria.size - 3}" else "")
            }
        }

        // 3. Анализ тегов
        if (requirement.tags.isNotEmpty()) {
            val testText = matches.joinToString(" ") {
                "${it.testChunk.testName} ${it.testChunk.content} ${it.testChunk.metadata.values.joinToString(" ")}"
            }.lowercase(Locale.getDefault())

            val uncoveredTags = requirement.tags.filter { tag ->
                !testText.contains(tag.lowercase(Locale.getDefault()), ignoreCase = true)
            }

            if (uncoveredTags.isNotEmpty() && uncoveredTags.size > requirement.tags.size / 2) {
                gaps.add("Не найдены тесты для ключевых тегов: ${uncoveredTags.joinToString(", ")}")
            }
        }

        // 4. Проверяем качество покрытия
        if (matches.none { it.confidence == ConfidenceLevel.HIGH }) {
            gaps.add("Отсутствуют тесты с высокой степенью уверенности в покрытии")
        }

        if (matches.all { it.similarityScore < 0.5 }) {
            gaps.add("Все найденные соответствия имеют низкое семантическое сходство (< 0.5)")
        }

        return gaps
    }

    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to",
            "for", "of", "with", "by", "as", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "should", "could", "can", "may", "might", "must",
            "this", "that", "these", "those", "their", "our", "your", "my",
            "и", "в", "на", "с", "к", "для", "от", "по", "не", "что", "как",
            "то", "все", "она", "они", "мы", "вы", "его", "ее", "их", "быть",
            "был", "была", "были", "есть", "быть", "иметь", "имеет", "имели"
        )

        return text.lowercase(Locale.getDefault())
            .split("\\W+".toRegex())
            .filter {
                it.length > 3 &&
                        it !in stopWords &&
                        !it.matches("\\d+".toRegex())
            }
            .distinct()
            .toSet()
    }

    private fun generateEvidence(
        requirement: BusinessRequirement,
        testChunk: TestChunk,
        similarity: Float
    ): String {
        return buildString {
            append("📝 Тест: ${testChunk.testName}\n")
            append("📁 Файл: ${testChunk.filePath}\n")
            append("🎯 Сходство: ${"%.3f".format(similarity)}\n")
            append("---\n")

            // Показываем контекст теста (первые 5 строк)
            val lines = testChunk.content.lines().take(5)
            if (lines.isNotEmpty()) {
                append("Код теста:\n")
                lines.forEach { line ->
                    append("  $line\n")
                }
                if (testChunk.content.lines().size > 5) {
                    append("  ... (еще ${testChunk.content.lines().size - 5} строк)\n")
                }
            }

            // Выделяем ключевые слова из критериев приемки
            val allKeywords = mutableSetOf<String>()
            allKeywords.addAll(extractKeywords(requirement.description))
            requirement.acceptanceCriteria.forEach { criterion ->
                allKeywords.addAll(extractKeywords(criterion))
            }

            val foundKeywords = allKeywords.filter { keyword ->
                testChunk.content.contains(keyword, ignoreCase = true) ||
                        testChunk.testName.contains(keyword, ignoreCase = true)
            }

            if (foundKeywords.isNotEmpty()) {
                append("🔑 Общие ключевые слова: ${foundKeywords.take(5).joinToString(", ")}")
                if (foundKeywords.size > 5) {
                    append(" и еще ${foundKeywords.size - 5}")
                }
                append("\n")
            }
        }
    }

    // AI анализ с учетом критериев приемки и тегов
    private fun analyzeWithAI(
        requirement: BusinessRequirement,
        matches: List<MatchResult>,
        aiService: AiService
    ): AIAnalysis {
        // Подготавливаем критерии приемки для анализа
        val criteriaText = if (requirement.acceptanceCriteria.isNotEmpty()) {
            """
            === КРИТЕРИИ ПРИЕМКИ ===
            ${requirement.acceptanceCriteria.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")}
            """.trimIndent()
        } else {
            "Критерии приемки не определены"
        }

        // Подготавливаем теги
        val tagsText = if (requirement.tags.isNotEmpty()) {
            "Теги: ${requirement.tags.joinToString(", ")}"
        } else {
            "Теги отсутствуют"
        }

        // Подготавливаем топ-3 теста для анализа
        val topTests = matches.take(3).joinToString("\n\n") { match ->
            """
            📝 ТЕСТ: ${match.testChunk.testName}
            📁 ФАЙЛ: ${match.testChunk.filePath}
            🎯 СХОДСТВО: ${"%.3f".format(match.similarityScore)}
            💪 УВЕРЕННОСТЬ: ${match.confidence}

            КОД ТЕСТА:
            ```${getFileExtension(match.testChunk.filePath)}
            ${match.testChunk.content.take(1000)}
            ```
            """.trimIndent()
        }

        // Расширенный промпт для AI с учетом критериев приемки
        val prompt = """
            Ты - старший QA инженер, проводящий аудит покрытия тестами.

            ПРОАНАЛИЗИРУЙ, насколько следующие ТЕСТЫ покрывают БИЗНЕС-ТРЕБОВАНИЕ и его КРИТЕРИИ ПРИЕМКИ:

            === БИЗНЕС-ТРЕБОВАНИЕ ===
            ID: ${requirement.id}
            Название: ${requirement.title}
            Описание: ${requirement.description}
            Категория: ${requirement.category}
            Приоритет: ${requirement.priority}
            $tagsText

            $criteriaText

            === ТЕСТЫ ===
            $topTests

            === ИНСТРУКЦИЯ ДЛЯ АНАЛИЗА ===
            Проведи детальный анализ и ответь СТРОГО в следующем формате JSON:
            {
              "coverage_assessment": "full|partial|none",
              "confidence": "high|medium|low",
              "covered_aspects": ["конкретный аспект 1", "конкретный аспект 2"],
              "missing_aspects": ["непокрытый аспект 1", "непокрытый аспект 2"],
              "covered_criteria": ["критерий 1", "критерий 2"],
              "missing_criteria": ["критерий 1", "критерий 2"],
              "explanation": "Подробное объяснение на русском языке с ссылками на код тестов",
              "recommendations": ["конкретная рекомендация 1", "конкретная рекомендация 2"]
            }

            КРИТЕРИИ ОЦЕНКИ:
            - "full": все критерии приемки покрыты тестами, высокое сходство (>0.7)
            - "partial": частичное покрытие критериев или среднее сходство (0.4-0.7)
            - "none": критерии не покрыты или сходство низкое (<0.4)

            БУДЬ КОНКРЕТНЫМ: указывай номера строк, названия методов, конкретные проверки в тестах.
        """.trimIndent()

        // Получаем ответ от AI
        val aiResponse = aiService.analyze(prompt)

        // Парсим ответ
        return parseAIResponse(aiResponse, requirement, matches)
    }

    private fun parseAIResponse(
        response: String,
        requirement: BusinessRequirement,
        matches: List<MatchResult>
    ): AIAnalysis {
        return try {
            // Пытаемся найти JSON в ответе
            val jsonRegex = "\\{[^}]*\\}".toRegex(RegexOption.DOT_MATCHES_ALL)
            val jsonMatch = jsonRegex.find(response)

            if (jsonMatch != null) {
                val jsonText = jsonMatch.value
                parseStructuredAIResponse(jsonText)
            } else {
                // Fallback: анализируем текстовый ответ
                parseTextAIResponse(response)
            }
        } catch (e: Exception) {
            // В случае ошибки создаем базовый анализ
            AIAnalysis(
                rawText = response,
                confidence = ConfidenceLevel.MEDIUM,
                coveredAspects = emptyList(),
                missingAspects = listOf("Не удалось разобрать AI ответ"),
                explanation = "Ошибка парсинга AI анализа: ${e.message}",
                recommendations = emptyList(),
                coveredCriteria = emptyList(),
                missingCriteria = emptyList()
            )
        }
    }

    private fun parseStructuredAIResponse(jsonText: String): AIAnalysis {
        // Простой парсинг JSON с использованием Jackson
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val jsonNode = mapper.readTree(jsonText)

            val coverageAssessment = jsonNode.get("coverage_assessment")?.asText() ?: "unknown"
            val confidenceText = jsonNode.get("confidence")?.asText() ?: "medium"
            val explanation = jsonNode.get("explanation")?.asText() ?: "Нет объяснения"

            val confidence = when (confidenceText.lowercase()) {
                "high" -> ConfidenceLevel.HIGH
                "medium" -> ConfidenceLevel.MEDIUM
                "low" -> ConfidenceLevel.LOW
                else -> ConfidenceLevel.MEDIUM
            }

            // Извлекаем массивы
            val coveredAspects = extractJsonArray(jsonNode, "covered_aspects")
            val missingAspects = extractJsonArray(jsonNode, "missing_aspects")
            val recommendations = extractJsonArray(jsonNode, "recommendations")
            val coveredCriteria = extractJsonArray(jsonNode, "covered_criteria")
            val missingCriteria = extractJsonArray(jsonNode, "missing_criteria")

            AIAnalysis(
                rawText = jsonText,
                confidence = confidence,
                coveredAspects = coveredAspects,
                missingAspects = missingAspects,
                explanation = explanation,
                recommendations = recommendations,
                coveredCriteria = coveredCriteria,
                missingCriteria = missingCriteria
            )
        } catch (e: Exception) {
            // Fallback на текстовый парсинг при ошибке
            parseTextAIResponse(jsonText)
        }
    }

    private fun extractJsonArray(jsonNode: com.fasterxml.jackson.databind.JsonNode, fieldName: String): List<String> {
        return try {
            val arrayNode = jsonNode.get(fieldName)
            if (arrayNode != null && arrayNode.isArray) {
                arrayNode.map { it.asText() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseTextAIResponse(text: String): AIAnalysis {
        // Эвристический анализ текстового ответа
        val confidence = when {
            text.contains("высок", ignoreCase = true) -> ConfidenceLevel.HIGH
            text.contains("средн", ignoreCase = true) -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val coveredAspects = mutableListOf<String>()
        val missingAspects = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        // Ищем ключевые фразы
        val lines = text.lines()
        var inCoveredSection = false
        var inMissingSection = false
        var inRecommendations = false

        for (line in lines) {
            when {
                line.contains("покрыт", ignoreCase = true) ||
                        line.contains("covered", ignoreCase = true) ||
                        line.contains("есть", ignoreCase = true) -> {
                    inCoveredSection = true
                    inMissingSection = false
                    inRecommendations = false
                }

                line.contains("не покрыт", ignoreCase = true) ||
                        line.contains("missing", ignoreCase = true) ||
                        line.contains("нет", ignoreCase = true) -> {
                    inCoveredSection = false
                    inMissingSection = true
                    inRecommendations = false
                }

                line.contains("рекоменд", ignoreCase = true) ||
                        line.contains("recommend", ignoreCase = true) -> {
                    inCoveredSection = false
                    inMissingSection = false
                    inRecommendations = true
                }

                line.contains("•") || line.contains("- ") || line.matches(".*\\d+\\..*".toRegex()) -> {
                    val aspect = line.substringAfter("•")
                        .substringAfter("- ")
                        .substringAfter(" ")
                        .trim()

                    if (aspect.isNotBlank()) {
                        if (inCoveredSection) coveredAspects.add(aspect)
                        if (inMissingSection) missingAspects.add(aspect)
                        if (inRecommendations) recommendations.add(aspect)
                    }
                }
            }
        }

        return AIAnalysis(
            rawText = text,
            confidence = confidence,
            coveredAspects = coveredAspects,
            missingAspects = missingAspects,
            explanation = "Текстовый анализ (структурированный ответ не найден). Анализ: $text.take(200)...",
            recommendations = recommendations,
            coveredCriteria = emptyList(),
            missingCriteria = emptyList()
        )
    }

    private fun getFileExtension(filePath: String): String {
        return filePath.substringAfterLast('.').lowercase()
    }

    // Статистика по анализу
    fun printAnalysisStatistics(reports: List<CoverageReport>) {
        println("\n📈 СТАТИСТИКА АНАЛИЗА:")
        println("=".repeat(50))

        val totalRequirements = reports.size
        val coveredRequirements = reports.count { it.coverageScore > 30 }
        val avgCoverage = reports.map { it.coverageScore }.average()

        println("📋 Всего требований: $totalRequirements")
        println("✅ Покрытых (>30%): $coveredRequirements (${"%.1f".format(coveredRequirements * 100.0 / totalRequirements)}%)")
        println("📊 Среднее покрытие: ${"%.1f".format(avgCoverage)}%")

        // Распределение по уровням уверенности
        val highConfidence = reports.count { it.confidence == ConfidenceLevel.HIGH }
        val mediumConfidence = reports.count { it.confidence == ConfidenceLevel.MEDIUM }
        val lowConfidence = reports.count { it.confidence == ConfidenceLevel.LOW }

        println("\n🎯 Уровень уверенности:")
        println("  Высокий: $highConfidence требований")
        println("  Средний: $mediumConfidence требований")
        println("  Низкий: $lowConfidence требований")

        // Статистика по критериям приемки
        val requirementsWithCriteria = reports.count { it.requirement.acceptanceCriteria.isNotEmpty() }
        if (requirementsWithCriteria > 0) {
            val avgCriteriaPerReq = reports.filter { it.requirement.acceptanceCriteria.isNotEmpty() }
                .map { it.requirement.acceptanceCriteria.size }.average()
            println("\n📋 Критерии приемки:")
            println("  Требований с критериями: $requirementsWithCriteria")
            println("  Среднее критериев на требование: ${"%.1f".format(avgCriteriaPerReq)}")
        }

        // AI анализ статистика
        val aiAnalyses = reports.mapNotNull { it.aiAnalysis }
        if (aiAnalyses.isNotEmpty()) {
            println("\n🤖 AI анализы: ${aiAnalyses.size} из $totalRequirements")
            val aiHighConfidence = aiAnalyses.count { it.confidence == ConfidenceLevel.HIGH }
            println("  AI высокая уверенность: $aiHighConfidence")
        }

        // Топ требований по покрытию
        val top5 = reports.sortedByDescending { it.coverageScore }.take(5)
        println("\n🏆 Топ-5 требований по покрытию:")
        top5.forEachIndexed { index, report ->
            println("  ${index + 1}. ${report.requirement.title} - ${"%.1f".format(report.coverageScore)}%")
        }

        // Требования с низким покрытием
        val lowCoverage = reports.filter { it.coverageScore < 20 }
        if (lowCoverage.isNotEmpty()) {
            println("\n⚠️  Требования с низким покрытием (<20%):")
            lowCoverage.forEach { report ->
                println("  • ${report.requirement.title} - ${"%.1f".format(report.coverageScore)}%")
            }
        }

        println("=".repeat(50))
    }
}

// Обновленная структура AIAnalysis с поддержкой критериев приемки
data class AIAnalysis(
    val rawText: String,
    val confidence: ConfidenceLevel,
    val coveredAspects: List<String>,
    val missingAspects: List<String>,
    val explanation: String,
    val recommendations: List<String>,
    val coveredCriteria: List<String> = emptyList(),
    val missingCriteria: List<String> = emptyList()
)

// Обновленная структура CoverageReport
data class CoverageReport(
    val requirement: BusinessRequirement,
    val matches: List<MatchResult>,
    val coverageScore: Float,
    val confidence: ConfidenceLevel,
    val gaps: List<String> = emptyList(),
    val aiAnalysis: AIAnalysis? = null,
    val timestamp: String = java.time.LocalDateTime.now().toString()
)