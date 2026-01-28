package analyze

import com.semantic.coverage.dto.ConfidenceLevel
import com.semantic.coverage.embedding.LocalEmbeddingService
import com.semantic.coverage.dto.MatchResult
import com.semantic.coverage.dto.TestChunk
import com.semantic.coverage.`ai-services`.AiService
import com.semantic.coverage.dto.Requirement
import java.util.*

class CoverageOpenAiAnalyzer (
    private val embeddingService: LocalEmbeddingService,
    private val aiService: AiService? = null,
    private val useAI: Boolean = false,
    private val similarityThreshold: Float = 0.3f
) {

    fun analyzeCoverage(
        requirements: List<Requirement>,
        testChunks: List<TestChunk>
    ): List<CoverageReport> {
        println("🧠 Начинаем анализ семантического покрытия...")
        println("   Требований: ${requirements.size}")
        println("   Тестовых чанков: ${testChunks.size}")
        println("   Использовать AI: $useAI")

        // 1. Векторизуем требования
        println("📊 Векторизация требований...")
        val requirementsWithEmbeddings = requirements.mapIndexed { index, req ->
            print("\r   Обработано ${index + 1}/${requirements.size} требований")
            req.copy(embedding = embeddingService.getEmbedding("${req.title}\n${req.description}"))
        }
        println()

        // 2. Векторизуем тесты (если еще не векторизованы)
        println("📊 Векторизация тестов...")
        val testChunksWithEmbeddings = testChunks.mapIndexed { index, chunk ->
            print("\r   Обработано ${index + 1}/${testChunks.size} тестов")
            if (chunk.embedding == null) {
                val enrichedContent = "${chunk.testName}\n${chunk.content}\n${chunk.metadata.values.joinToString(" ")}"
                chunk.copy(embedding = embeddingService.getEmbedding(enrichedContent))
            } else {
                chunk
            }
        }
        println()

        // 3. Для каждого требования ищем соответствия
        println("🔍 Поиск соответствий...")
        return requirementsWithEmbeddings.mapIndexed { index, requirement ->
            println("   Требование ${index + 1}/${requirements.size}: ${requirement.title}")

            val matches = findMatchesForRequirement(requirement, testChunksWithEmbeddings)
            val coverageScore = calculateCoverageScore(matches)
            val gaps = identifyGaps(requirement, matches)

            // AI анализ (если включен и есть соответствия)
            val aiAnalysis = if (useAI && aiService != null && matches.isNotEmpty()) {
                println("     🤖 Запуск AI анализа...")
                try {
                    analyzeWithAI(requirement, matches, aiService)
                } catch (e: Exception) {
                    println("     ⚠️  Ошибка AI анализа: ${e.message}")
                    null
                }
            } else {
                null
            }

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
        requirement: Requirement,
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
                requirement.embedding!!,
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

    private fun calculateCoverageScore(matches: List<MatchResult>): Float {
        if (matches.isEmpty()) return 0.0f

        val highConfidenceMatches = matches.filter { it.confidence == ConfidenceLevel.HIGH }
        val mediumConfidenceMatches = matches.filter { it.confidence == ConfidenceLevel.MEDIUM }

        // Взвешенная формула
        val weightedMatches = (highConfidenceMatches.size * 1.0f) +
                (mediumConfidenceMatches.size * 0.6f) +
                (matches.size * 0.3f)

        val maxPossible = matches.size * 1.9f // Максимальный возможный вес

        val weightedScore = if (maxPossible > 0) weightedMatches / maxPossible else 0.0f

        // Учитываем максимальное сходство
        val maxSimilarity = matches.maxOfOrNull { it.similarityScore } ?: 0.0f

        // Комбинированная оценка
        return ((weightedScore * 0.6f) + (maxSimilarity * 0.4f)) * 100f
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
        requirement: Requirement,
        matches: List<MatchResult>,
        aiAnalysis: AIAnalysis? = null
    ): List<String> {
        val gaps = mutableListOf<String>()

        // 1. Проверяем наличие соответствий
        if (matches.isEmpty()) {
            gaps.add("Требование не имеет ни одного связанного теста")
            return gaps
        }

        // 2. Анализ ключевых слов
        val keywords = extractKeywords(requirement.description)
        val matchedKeywords = mutableSetOf<String>()

        matches.forEach { match ->
            keywords.forEach { keyword ->
                val testText = "${match.testChunk.testName} ${match.testChunk.content}"
                if (testText.contains(keyword, ignoreCase = true)) {
                    matchedKeywords.add(keyword)
                }
            }
        }

        // 3. Находим непокрытые ключевые слова
        val unmatchedKeywords = keywords - matchedKeywords
        if (unmatchedKeywords.isNotEmpty() && unmatchedKeywords.size > keywords.size / 2) {
            gaps.add("Не найдены тесты для ключевых понятий: ${unmatchedKeywords.joinToString(", ")}")
        }

        // 4. Проверяем качество покрытия
        if (matches.none { it.confidence == ConfidenceLevel.HIGH }) {
            gaps.add("Отсутствуют тесты с высокой степенью уверенности в покрытии")
        }

        if (matches.all { it.similarityScore < 0.5 }) {
            gaps.add("Все найденные соответствия имеют низкое семантическое сходство (< 0.5)")
        }

        // 5. Используем AI анализ для выявления пробелов
        aiAnalysis?.missingAspects?.takeIf { it.isNotEmpty() }?.let { missingAspects ->
            if (missingAspects.size > 2) {
                gaps.add("AI выявил ${missingAspects.size} непокрытых аспектов требования")
            }
        }

        return gaps
    }

    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to",
            "for", "of", "with", "by", "as", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "should", "could", "can", "may", "might", "must",
            "this", "that", "these", "those", "their", "our", "your", "my"
        )

        return text.lowercase(Locale.getDefault())
            .split("\\W+".toRegex())
            .filter {
                it.length > 3 &&
                        it !in stopWords &&
                        !it.matches("\\d+".toRegex()) &&
                        !it.matches(".*[^a-zA-Zа-яА-Я].*".toRegex())
            }
            .distinct()
            .toSet()
    }

    private fun generateEvidence(
        requirement: Requirement,
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

            // Выделяем ключевые слова
            val keywords = extractKeywords(requirement.description)
            val foundKeywords = keywords.filter { keyword ->
                testChunk.content.contains(keyword, ignoreCase = true) ||
                        testChunk.testName.contains(keyword, ignoreCase = true)
            }

            if (foundKeywords.isNotEmpty()) {
                append("🔑 Общие ключевые слова: ${foundKeywords.joinToString(", ")}\n")
            }
        }
    }

    // AI анализ
    private fun analyzeWithAI(
        requirement: Requirement,
        matches: List<MatchResult>,
        aiService: AiService
    ): AIAnalysis {
        // Подготавливаем топ-3 теста для анализа
        val topTests = matches.take(3).joinToString("\n\n") { match ->
            """
            📝 ТЕСТ: ${match.testChunk.testName}
            📁 ФАЙЛ: ${match.testChunk.filePath}
            🎯 СХОДСТВО С ТРЕБОВАНИЕМ: ${"%.3f".format(match.similarityScore)}
            📊 УВЕРЕННОСТЬ: ${match.confidence}

            КОД ТЕСТА:
            ```${getFileExtension(match.testChunk.filePath)}
            ${match.testChunk.content.take(800)}
            ```
            """.trimIndent()
        }

        // Формируем промпт для AI
        val prompt = """
            Ты - старший QA инженер, проводящий аудит покрытия тестами.

            ПРОАНАЛИЗИРУЙ, насколько следующие ТЕСТЫ покрывают БИЗНЕС-ТРЕБОВАНИЕ:

            === БИЗНЕС-ТРЕБОВАНИЕ ===
            Название: ${requirement.title}
            Описание: ${requirement.description}
            ID: ${requirement.id}

            === ТЕСТЫ ===
            $topTests

            === ИНСТРУКЦИЯ ДЛЯ АНАЛИЗА ===
            Проведи детальный анализ и ответь СТРОГО в следующем формате JSON:
            {
              "coverage_assessment": "full|partial|none",
              "confidence": "high|medium|low",
              "covered_aspects": ["аспект 1", "аспект 2", ...],
              "missing_aspects": ["аспект 1", "аспект 2", ...],
              "explanation": "Текстовое объяснение на русском языке",
              "recommendations": ["рекомендация 1", "рекомендация 2", ...]
            }

            КРИТЕРИИ:
            1. "full" - требование полностью покрыто тестами
            2. "partial" - требование частично покрыто
            3. "none" - требование не покрыто

            УВЕРЕННОСТЬ:
            1. "high" - высокий уровень уверенности в анализе
            2. "medium" - средний уровень уверенности
            3. "low" - низкий уровень уверенности

            Будь максимально конкретным и ссылайся на код тестов.
        """.trimIndent()

        // Получаем ответ от AI
        val aiResponse = aiService.analyze(prompt)

        // Парсим ответ
        return parseAIResponse(aiResponse, requirement, matches)
    }

    private fun parseAIResponse(
        response: String,
        requirement: Requirement,
        matches: List<MatchResult>
    ): AIAnalysis {
        return try {
            // Пытаемся найти JSON в ответе
            val jsonRegex = "\\{.*\\}".toRegex(RegexOption.DOT_MATCHES_ALL)
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
                explanation = "Ошибка парсинга AI анализа",
                recommendations = emptyList()
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

            AIAnalysis(
                rawText = jsonText,
                confidence = confidence,
                coveredAspects = coveredAspects,
                missingAspects = missingAspects,
                explanation = explanation,
                recommendations = recommendations
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

        // Ищем ключевые фразы
        val lines = text.lines()
        var inCoveredSection = false
        var inMissingSection = false

        for (line in lines) {
            when {
                line.contains("покрыт", ignoreCase = true) ||
                        line.contains("covered", ignoreCase = true) ||
                        line.contains("есть", ignoreCase = true) -> {
                    inCoveredSection = true
                    inMissingSection = false
                }
                line.contains("не покрыт", ignoreCase = true) ||
                        line.contains("missing", ignoreCase = true) ||
                        line.contains("нет", ignoreCase = true) -> {
                    inCoveredSection = false
                    inMissingSection = true
                }
                line.contains("•") || line.contains("- ") || line.matches(".*\\d+\\..*".toRegex()) -> {
                    val aspect = line.substringAfter("•")
                        .substringAfter("- ")
                        .substringAfter(" ")
                        .trim()

                    if (aspect.isNotBlank()) {
                        if (inCoveredSection) coveredAspects.add(aspect)
                        if (inMissingSection) missingAspects.add(aspect)
                    }
                }
            }
        }

        return AIAnalysis(
            rawText = text,
            confidence = confidence,
            coveredAspects = coveredAspects,
            missingAspects = missingAspects,
            explanation = "Текстовый анализ (структурированный ответ не найден)",
            recommendations = emptyList()
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

// Обновленная структура AIAnalysis
data class AIAnalysis(
    val rawText: String,
    val confidence: ConfidenceLevel,
    val coveredAspects: List<String>,
    val missingAspects: List<String>,
    val explanation: String,
    val recommendations: List<String>
)

// Обновленная структура CoverageReport
data class CoverageReport(
    val requirement: Requirement,
    val matches: List<MatchResult>,
    val coverageScore: Float,
    val confidence: ConfidenceLevel,
    val gaps: List<String> = emptyList(),
    val aiAnalysis: AIAnalysis? = null,
    val timestamp: String = java.time.LocalDateTime.now().toString()
)