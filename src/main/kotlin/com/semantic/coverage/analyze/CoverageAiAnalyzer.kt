package com.semantic.coverage.analyze

import com.semantic.coverage.aiServices.AiService
import com.semantic.coverage.dto.*
import com.semantic.coverage.embedding.EmbeddingService
import java.util.*

class CoverageAiAnalyzer(
    private val embeddingService: EmbeddingService,
    private val aiService: AiService? = null,
    private val useAI: Boolean = false,
    private val similarityThreshold: Float = 0.25f
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
            val requirementText = req.getFullTextForEmbedding()
            req.copy(embedding = embeddingService.getTextEmbedding(requirementText))
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
                // Используем один сервис для требований и кода
                chunk.copy(embedding = embeddingService.getTextEmbedding(enrichedContent))
            } else {
                chunk
            }
        }
        println()

        // 3. Для каждого требования ищем соответствия
        println("🔍 Поиск соответствий...")
        return requirementsWithEmbeddings.mapIndexed { index, requirement ->
            println("\n📋 Требование ${index + 1}/${requirements.size}: ${requirement.title}")

            val matches = findMatchesForRequirement(requirement, testChunksWithEmbeddings)
            println("   Найдено соответствий: ${matches.size}")

            val coverageScore = calculateCoverageScore(requirement, matches)
            println("   Оценка покрытия: ${"%.1f".format(coverageScore)}%")

            val gaps = identifyGaps(requirement, matches)
            if (gaps.isNotEmpty()) {
                println("   Пробелы: ${gaps.take(2).joinToString("; ")}")
            }

            // AI анализ (если включен)
            val aiAnalysis = if (useAI && aiService != null) {
                println("🤖 Запуск расширенного AI анализа...")
                try {
                    val analysis = analyzeWithAI(requirement, matches, aiService)

                    // Логируем результат AI анализа
                    println("   ✅ AI анализ завершен")
                    println("   📊 Результаты:")
                    println("     - Уверенность: ${analysis.confidence}")
                    println("     - Покрытых аспектов: ${analysis.coveredAspects.size}")
                    println("     - Рекомендаций: ${analysis.recommendations.size}")
                    println("     - Объяснение: ${analysis.explanation.take(100)}...")

                    if (analysis.coveredAspects.isEmpty()) {
                        println("   ⚠️  Предупреждение: покрытые аспекты пусты")
                    }
                    if (analysis.recommendations.isEmpty()) {
                        println("   ⚠️  Предупреждение: рекомендации пусты")
                    }

                    analysis
                } catch (e: Exception) {
                    println("❌ Ошибка AI анализа: ${e.message}")
                    println("   Stack trace: ${e.stackTrace.take(5).joinToString("\n   ")}")
                    null
                }
            } else {
                println("ℹ️  AI анализ отключен")
                null
            }

            val confidence = calculateConfidenceLevel(matches, aiAnalysis)
            println("   🎯 Итоговая уверенность: $confidence")

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
        topK: Int = 15
    ): List<MatchResult> {
        require(requirement.embedding != null) { "Requirement must have embedding" }

        if (testChunks.isEmpty()) return emptyList()

        // Фильтруем тесты с векторами
        val testChunksWithEmbeddings = testChunks.filter { it.embedding != null }

        if (testChunksWithEmbeddings.isEmpty()) return emptyList()

        println("   🔍 Сравниваем с ${testChunksWithEmbeddings.size} тестами...")

        // Вычисляем семантическое сходство
        val matchResults = testChunksWithEmbeddings.map { chunk ->
            val similarity = embeddingService.cosineSimilarity(
                requirement.embedding!!,
                chunk.embedding!!
            )

            val confidence = when {
                similarity > 0.6 -> {
                    println("     ✅ Высокое сходство (${"%.3f".format(similarity)}): ${chunk.testName}")
                    ConfidenceLevel.HIGH
                }
                similarity > 0.35 -> {
                    println("     ⚠️  Среднее сходство (${"%.3f".format(similarity)}): ${chunk.testName}")
                    ConfidenceLevel.MEDIUM
                }
                else -> {
                    if (similarity > 0.25) {
                        println("     📝 Низкое сходство (${"%.3f".format(similarity)}): ${chunk.testName}")
                    }
                    ConfidenceLevel.LOW
                }
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
        val filteredResults = matchResults
            .filter { it.similarityScore >= similarityThreshold }
            .sortedByDescending { it.similarityScore }
            .take(topK)

        println("   📊 После фильтрации: ${filteredResults.size} соответствий (порог: $similarityThreshold)")

        return filteredResults
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
                                    embeddingService.getTextEmbedding(criterion),
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
            highCount >= 1 && avgSimilarity > 0.5 -> ConfidenceLevel.HIGH
            (highCount + mediumCount) >= 2 && avgSimilarity > 0.3 -> ConfidenceLevel.MEDIUM
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
                                    embeddingService.getTextEmbedding(criterion),
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

        if (matches.all { it.similarityScore < 0.4 }) {
            gaps.add("Все найденные соответствия имеют низкое семантическое сходство (< 0.4)")
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
        val topTests = if (matches.isNotEmpty()) {
            matches.take(3).joinToString("\n\n") { match ->
                """
                📝 ТЕСТ: ${match.testChunk.testName}
                📁 ФАЙЛ: ${match.testChunk.filePath}
                🎯 СХОДСТВО: ${"%.3f".format(match.similarityScore)}
                💪 УВЕРЕННОСТЬ: ${match.confidence}

                КОД ТЕСТА:
                ```${getFileExtension(match.testChunk.filePath)}
                ${match.testChunk.content.take(800)}
                ```
                """.trimIndent()
            }
        } else {
            "Нет найденных тестов для этого требования"
        }

        // УПРОЩЕННЫЙ и более четкий промпт
        val prompt = """
            Ты - старший QA инженер. Проанализируй покрытие тестами.
            
            ВАЖНО: Ответь ТОЛЬКО в формате JSON без дополнительного текста!
            
            JSON должен содержать ВСЕ эти поля:
            {
              "coverage_assessment": "full|partial|none",
              "confidence": "high|medium|low",
              "covered_aspects": ["конкретный аспект 1", "конкретный аспект 2"],
              "missing_aspects": ["аспект который не покрыт", "другой непокрытый аспект"],
              "covered_criteria": ["критерий 1", "критерий 2"],
              "missing_criteria": ["непокрытый критерий"],
              "explanation": "Краткое объяснение на русском языке",
              "recommendations": ["конкретная рекомендация 1", "конкретная рекомендация 2"]
            }
            
            Правила заполнения:
            1. covered_aspects: минимум 2 конкретных аспекта требования, которые покрыты тестами
            2. missing_aspects: минимум 1 аспект, который не покрыт
            3. recommendations: минимум 2 конкретные рекомендации по улучшению тестов
            
            Если информации недостаточно, используй эти дефолтные значения:
            - covered_aspects: ["базовая функциональность", "положительные сценарии"]
            - missing_aspects: ["обработка ошибок", "edge cases"]
            - recommendations: ["добавить тесты на edge cases", "увеличить покрытие исключений"]
            
            === ТРЕБОВАНИЕ ===
            ID: ${requirement.id}
            Название: ${requirement.title}
            Описание: ${requirement.description}
            $criteriaText
            $tagsText
            
            === ТЕСТЫ ===
            $topTests
        """.trimIndent()

        // Получаем ответ от AI
        println("   📤 Отправка запроса к AI...")
        val aiResponse = aiService.analyze(prompt)
        println("   📥 Получен ответ длиной ${aiResponse.length} символов")

        // Логируем для отладки
        if (aiResponse.length > 200) {
            println("   📝 Начало ответа: ${aiResponse.take(200)}...")
        } else {
            println("   📝 Ответ: $aiResponse")
        }

        // Парсим ответ
        return parseAIResponse(aiResponse, requirement, matches)
    }

    private fun parseAIResponse(
        response: String,
        requirement: BusinessRequirement,
        matches: List<MatchResult>
    ): AIAnalysis {
        return try {
            println("   🛠️  Начинаем парсинг AI ответа...")

            // 1. Пытаемся найти JSON (более гибкий поиск)
            val jsonMatch = findJsonInResponse(response)

            if (jsonMatch != null) {
                println("   ✅ Найден структурированный JSON ответ")
                return parseStructuredAIResponse(jsonMatch)
            }

            // 2. Пытаемся найти Markdown с JSON
            val markdownJson = extractJsonFromMarkdown(response)
            if (markdownJson != null) {
                println("   ✅ Найден JSON в Markdown ответе")
                return parseStructuredAIResponse(markdownJson)
            }

            // 3. Парсим текстовый ответ с улучшенной логикой
            println("   ⚠️  JSON не найден, парсим текстовый ответ")
            parseEnhancedTextAIResponse(response)

        } catch (e: Exception) {
            println("   ❌ Ошибка парсинга AI ответа: ${e.message}")
            // Возвращаем анализ с информацией об ошибке
            AIAnalysis(
                rawText = response.take(500) + (if (response.length > 500) "..." else ""),
                confidence = ConfidenceLevel.LOW,
                coveredAspects = listOf("Не удалось разобрать ответ AI"),
                missingAspects = listOf("Ошибка парсинга: ${e.message}"),
                explanation = "AI ответ не соответствует ожидаемому формату. Ответ начинается с: ${response.take(100)}...",
                recommendations = listOf(
                    "Проверьте формат ответа AI",
                    "Убедитесь, что AI возвращает корректный JSON",
                    "Попробуйте упростить промпт"
                ),
                coveredCriteria = emptyList(),
                missingCriteria = emptyList()
            )
        }
    }

    /**
     * Находит JSON в ответе (более гибкий поиск)
     */
    private fun findJsonInResponse(response: String): String? {
        // Вариант 1: Ищем чистый JSON
        try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}')

            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val possibleJson = response.substring(jsonStart, jsonEnd + 1)

                // Проверяем, что это похоже на наш JSON
                if (possibleJson.contains("\"coverage_assessment\"") ||
                    possibleJson.contains("\"covered_aspects\"") ||
                    possibleJson.contains("\"recommendations\"")) {

                    // Проверяем валидность JSON
                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                    mapper.readTree(possibleJson) // Если не выбросит исключение, JSON валиден
                    return possibleJson
                }
            }
        } catch (e: Exception) {
            // Невалидный JSON, продолжаем поиск
        }

        // Вариант 2: Ищем JSON с кодом языка
        val codeBlockPattern = "```(?:json)?\\s*(\\{.*?\\})\\s*```".toRegex(RegexOption.DOT_MATCHES_ALL)
        val codeMatch = codeBlockPattern.find(response)
        if (codeMatch != null) {
            return codeMatch.groupValues[1].trim()
        }

        return null
    }

    /**
     * Извлекает JSON из Markdown ответа
     */
    private fun extractJsonFromMarkdown(response: String): String? {
        val lines = response.lines()
        var inJsonBlock = false
        val jsonLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()

            when {
                trimmedLine.startsWith("```json") || trimmedLine.startsWith("```") -> {
                    if (!inJsonBlock) {
                        inJsonBlock = true
                    } else {
                        inJsonBlock = false
                        val json = jsonLines.joinToString("\n")
                        if (json.contains("{") && json.contains("}")) {
                            return json
                        }
                        jsonLines.clear()
                    }
                }
                inJsonBlock -> {
                    jsonLines.add(line)
                }
            }
        }

        return null
    }

    /**
     * Улучшенный парсинг текстового ответа
     */
    private fun parseEnhancedTextAIResponse(text: String): AIAnalysis {
        val lines = text.lines()

        // Ищем confidence в тексте
        val confidence = when {
            text.contains("высок", ignoreCase = true) ||
                    text.contains("high", ignoreCase = true) ||
                    text.contains("полное", ignoreCase = true) ||
                    text.contains("отличн", ignoreCase = true) -> ConfidenceLevel.HIGH

            text.contains("средн", ignoreCase = true) ||
                    text.contains("medium", ignoreCase = true) ||
                    text.contains("частичное", ignoreCase = true) ||
                    text.contains("умерен", ignoreCase = true) -> ConfidenceLevel.MEDIUM

            else -> ConfidenceLevel.LOW
        }

        // Извлекаем покрытые аспекты
        val coveredAspects: MutableList<String> = extractSectionsFromText(lines, listOf(
            "✅ покрытые аспекты",
            "покрытые аспекты:",
            "covered aspects:",
            "что покрыто:",
            "тесты проверяют:",
            "охвачены:"
        ))

        // Если не нашли, пробуем извлечь из общего текста
        if (coveredAspects.isEmpty()) {
            coveredAspects.addAll(extractBulletPoints(text, listOf("проверяет", "охватывает", "тестирует")))
        }

        // Извлекаем непокрытые аспекты
        val missingAspects = extractSectionsFromText(lines, listOf(
            "⚠️ непокрытые аспекты",
            "непокрытые аспекты:",
            "missing aspects:",
            "что не покрыто:",
            "нужно добавить:",
            "отсутствует:"
        ))

        // Если не нашли, используем дефолтные
        if (missingAspects.isEmpty()) {
            missingAspects.add("обработка ошибок и исключительных ситуаций")
            missingAspects.add("пограничные случаи (edge cases)")
        }

        // Извлекаем рекомендации
        val recommendations = extractSectionsFromText(lines, listOf(
            "💡 рекомендации",
            "рекомендации:",
            "recommendations:",
            "советы:",
            "что улучшить:",
            "предложения:"
        ))

        // Если не нашли, используем дефолтные
        if (recommendations.isEmpty()) {
            recommendations.addAll(extractBulletPoints(text, listOf("рекомендуется", "следует", "нужно", "стоит")))
            if (recommendations.isEmpty()) {
                recommendations.add("добавить тесты на обработку ошибок")
                recommendations.add("проверить покрытие edge cases")
                recommendations.add("увеличить разнообразие тестовых данных")
            }
        }

        // Формируем объяснение
        val explanation = if (text.length > 300) {
            val firstParagraph = text.split("\n\n").firstOrNull() ?: text.take(300)
            firstParagraph.take(250) + "..."
        } else {
            text
        }

        return AIAnalysis(
            rawText = text,
            confidence = confidence,
            coveredAspects = coveredAspects.take(5), // Ограничиваем количество
            missingAspects = missingAspects.take(3),
            explanation = explanation,
            recommendations = recommendations.take(3),
            coveredCriteria = emptyList(),
            missingCriteria = emptyList()
        )
    }

    /**
     * Извлекает секции из текста
     */
    private fun extractSectionsFromText(lines: List<String>, triggers: List<String>): MutableList<String> {
        val sections = mutableListOf<String>()
        var inSection = false
        var sectionStart = -1

        for ((index, line) in lines.withIndex()) {
            val trimmedLine = line.trim()

            // Проверяем, начинается ли новая секция
            if (triggers.any { trigger ->
                    trimmedLine.lowercase().contains(trigger.lowercase())
                }) {
                inSection = true
                sectionStart = index
                continue
            }

            // Если мы в секции и строка содержит пункты
            if (inSection && sectionStart != -1 && index > sectionStart) {
                // Проверяем, не началась ли следующая секция
                if (trimmedLine.contains(":") && trimmedLine.length < 50) {
                    // Возможно, это начало новой секции
                    val newSectionTriggers = listOf("рекомендации", "recommendations", "советы",
                        "непокрытые", "missing", "покрытые", "covered")
                    if (newSectionTriggers.any { trimmedLine.lowercase().contains(it) }) {
                        inSection = false
                        continue
                    }
                }

                // Извлекаем пункты
                extractBulletItems(trimmedLine).forEach { item ->
                    if (item.isNotBlank() && item.length > 3) {
                        sections.add(item)
                    }
                }
            }

            // Если нашли пустую строку после нескольких пунктов, возможно, секция закончилась
            if (inSection && trimmedLine.isBlank() && sections.isNotEmpty() &&
                index > sectionStart + 3) {
                inSection = false
            }
        }

        return sections.distinct().toMutableList()
    }

    /**
     * Извлекает пункты из строки
     */
    private fun extractBulletItems(line: String): List<String> {
        val items = mutableListOf<String>()

        // Разные форматы пунктов
        val bulletPatterns = listOf(
            Regex("^[\\-•*]\\s+(.+)"),
            Regex("^\\d+\\.\\s+(.+)"),
            Regex("^\\[\\d+\\]\\s+(.+)")
        )

        for (pattern in bulletPatterns) {
            val match = pattern.find(line)
            if (match != null) {
                items.add(match.groupValues[1].trim())
                return items
            }
        }

        // Если не нашли форматированные пункты, но строка короткая и значимая
        if (line.isNotBlank() && line.length in 5..100 &&
            !line.contains(":") && !line.endsWith(".")) {
            items.add(line)
        }

        return items
    }

    /**
     * Извлекает bullet points из текста по ключевым словам
     */
    private fun extractBulletPoints(text: String, keywords: List<String>): MutableList<String> {
        val points = mutableListOf<String>()
        val lines = text.lines()

        for ((index, line) in lines.withIndex()) {
            val trimmedLine = line.trim()

            // Ищем строки с ключевыми словами
            if (keywords.any { trimmedLine.lowercase().contains(it) }) {
                // Смотрим следующие строки на предмет пунктов
                for (nextLineIndex in index + 1 until minOf(index + 5, lines.size)) {
                    val nextLine = lines[nextLineIndex].trim()
                    val bulletItems = extractBulletItems(nextLine)
                    points.addAll(bulletItems)

                    if (bulletItems.isEmpty() && nextLine.isNotBlank()) {
                        // Возможно, это продолжение текста
                        points.add(nextLine.take(100))
                    }
                }
                break
            }
        }

        return points.distinct().take(5).toMutableList()
    }

    private fun parseStructuredAIResponse(jsonText: String): AIAnalysis {
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val jsonNode = mapper.readTree(jsonText)

            val coverageAssessment = jsonNode.get("coverage_assessment")?.asText() ?: "partial"
            val confidenceText = jsonNode.get("confidence")?.asText() ?: "medium"
            val explanation = jsonNode.get("explanation")?.asText() ?: "Нет объяснения"

            val confidence = when (confidenceText.lowercase()) {
                "high" -> ConfidenceLevel.HIGH
                "medium" -> ConfidenceLevel.MEDIUM
                "low" -> ConfidenceLevel.LOW
                else -> ConfidenceLevel.MEDIUM
            }

            // Извлекаем массивы с обработкой ошибок
            val coveredAspects = extractJsonArraySafe(jsonNode, "covered_aspects")
                .ifEmpty { listOf("основная функциональность", "положительные сценарии") }

            val missingAspects = extractJsonArraySafe(jsonNode, "missing_aspects")
                .ifEmpty { listOf("обработка ошибок", "edge cases") }

            val recommendations = extractJsonArraySafe(jsonNode, "recommendations")
                .ifEmpty { listOf("добавить тесты на edge cases", "увеличить покрытие исключений") }

            val coveredCriteria = extractJsonArraySafe(jsonNode, "covered_criteria")
            val missingCriteria = extractJsonArraySafe(jsonNode, "missing_criteria")

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
            println("   ⚠️  Ошибка парсинга JSON: ${e.message}")
            // Fallback на текстовый парсинг при ошибке
            parseEnhancedTextAIResponse(jsonText)
        }
    }

    private fun extractJsonArraySafe(jsonNode: com.fasterxml.jackson.databind.JsonNode, fieldName: String): List<String> {
        return try {
            val arrayNode = jsonNode.get(fieldName)
            if (arrayNode != null && arrayNode.isArray) {
                arrayNode.map {
                    val text = it.asText()
                    if (text.isNotBlank()) text.trim() else null
                }.filterNotNull()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getFileExtension(filePath: String): String {
        return filePath.substringAfterLast('.').lowercase()
    }

    // Статистика по анализу
    fun printAnalysisStatistics(reports: List<CoverageReport>) {
        println("\n📈 СТАТИСТИКА АНАЛИЗА:")
        println("=".repeat(50))

        val totalRequirements = reports.size
        val coveredRequirements = reports.count { it.coverageScore > 20 }
        val avgCoverage = reports.map { it.coverageScore }.average()

        println("📋 Всего требований: $totalRequirements")
        println("✅ Покрытых (>20%): $coveredRequirements (${"%.1f".format(coveredRequirements * 100.0 / totalRequirements)}%)")
        println("📊 Среднее покрытие: ${"%.1f".format(avgCoverage)}%")

        // Распределение по уровням уверенности
        val highConfidence = reports.count { it.confidence == ConfidenceLevel.HIGH }
        val mediumConfidence = reports.count { it.confidence == ConfidenceLevel.MEDIUM }
        val lowConfidence = reports.count { it.confidence == ConfidenceLevel.LOW }

        println("\n🎯 Уровень уверенности:")
        println("  Высокий: $highConfidence требований")
        println("  Средний: $mediumConfidence требований")
        println("  Низкий: $lowConfidence требований")

        // Статистика по AI анализу
        val aiAnalyses = reports.mapNotNull { it.aiAnalysis }
        if (aiAnalyses.isNotEmpty()) {
            println("\n🤖 AI анализы: ${aiAnalyses.size} из $totalRequirements")
            val aiWithCoveredAspects = aiAnalyses.count { it.coveredAspects.isNotEmpty() }
            val aiWithRecommendations = aiAnalyses.count { it.recommendations.isNotEmpty() }
            println("  С покрытыми аспектами: $aiWithCoveredAspects")
            println("  С рекомендациями: $aiWithRecommendations")

            // Собираем все рекомендации для анализа
            val allRecommendations = aiAnalyses.flatMap { it.recommendations }
            if (allRecommendations.isNotEmpty()) {
                val topRecommendations = allRecommendations
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)

                println("\n  🏆 Топ-5 рекомендаций:")
                topRecommendations.forEach { (rec, count) ->
                    println("    • $rec ($count требований)")
                }
            }
        }

        // Топ требований по покрытию
        val top5 = reports.sortedByDescending { it.coverageScore }.take(5)
        println("\n🏆 Топ-5 требований по покрытию:")
        top5.forEachIndexed { index, report ->
            println("  ${index + 1}. ${report.requirement.title} - ${"%.1f".format(report.coverageScore)}% (${report.matches.size} тестов)")
        }

        // Требования с низким покрытием
        val lowCoverage = reports.filter { it.coverageScore < 15 }
        if (lowCoverage.isNotEmpty()) {
            println("\n⚠️  Требования с низким покрытием (<15%):")
            lowCoverage.forEach { report ->
                println("  • ${report.requirement.title} - ${"%.1f".format(report.coverageScore)}%")
            }
        }

        // Статистика по соответствиям
        val totalMatches = reports.sumOf { it.matches.size }
        val avgMatches = if (totalRequirements > 0) totalMatches.toDouble() / totalRequirements else 0.0
        println("\n🔗 Среднее соответствий на требование: ${"%.1f".format(avgMatches)}")

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