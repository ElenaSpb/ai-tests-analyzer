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

        // Улучшенный промпт с четкими инструкциями
        val prompt = """
            Ты - старший QA инженер. Проанализируй покрытие тестами.
            
            ВАЖНО: 
            1. Ответь ТОЛЬКО в формате JSON без дополнительного текста!
            2. Не используй многоточия (...) нигде в ответе!
            3. Все строки должны быть полными и завершенными.
            
            Формат ответа:
            {
              "coverage_assessment": "full|partial|none",
              "confidence": "high|medium|low",
              "covered_aspects": ["конкретный аспект 1", "конкретный аспект 2"],
              "missing_aspects": ["аспект который не покрыт", "другой непокрытый аспект"],
              "covered_criteria": ["критерий 1", "критерий 2"],
              "missing_criteria": ["непокрытый критерий"],
              "explanation": "Краткое объяснение на русском языке, не более 2 предложений",
              "recommendations": ["конкретная рекомендация 1", "конкретная рекомендация 2"]
            }
            
            Правила:
            1. Все строки должны быть короткими (до 50 символов)
            2. ЗАПРЕЩЕНО использовать многоточия
            3. Объяснение должно быть кратким и понятным
            4. Все массивы должны содержать минимум 2 элемента
            
            === ТРЕБОВАНИЕ ===
            ID: ${requirement.id}
            Название: ${requirement.title}
            Описание: ${requirement.description.take(500)}
            
            $criteriaText
            $tagsText
            
            === ТЕСТЫ ===
            $topTests
            
            Проанализируй и верни ответ в указанном JSON формате.
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

            // 1. Очищаем ответ от некорректных символов
            val cleanedResponse = cleanAIResponse(response)

            // 2. Пытаемся найти JSON
            val jsonMatch = findJsonInResponse(cleanedResponse)

            if (jsonMatch != null) {
                println("   ✅ Найден структурированный JSON ответ")
                return parseStructuredAIResponse(jsonMatch)
            }

            // 3. Пытаемся найти Markdown с JSON
            val markdownJson = extractJsonFromMarkdown(cleanedResponse)
            if (markdownJson != null) {
                println("   ✅ Найден JSON в Markdown ответе")
                return parseStructuredAIResponse(markdownJson)
            }

            // 4. Парсим текстовый ответ
            println("   ⚠️  JSON не найден, парсим текстовый ответ")
            parseEnhancedTextAIResponse(cleanedResponse)

        } catch (e: Exception) {
            println("   ❌ Ошибка парсинга AI ответа: ${e.message}")
            createFallbackAnalysis(response)
        }
    }

    /**
     * Очищает ответ AI от некорректных символов
     */
    private fun cleanAIResponse(response: String): String {
        return response
            .replace("...", ".")
            .replace("\\.\\.\\.", ".")
            .replace("..", ".")
            .replace(Regex("\\s+"), " ")
            .replace("\"\"", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    /**
     * Находит JSON в ответе
     */
    private fun findJsonInResponse(response: String): String? {
        try {
            // Ищем начало JSON
            val jsonStart = response.indexOf('{')
            if (jsonStart == -1) return null

            // Находим баланс фигурных скобок
            var braceCount = 0
            var jsonEnd = -1

            for (i in jsonStart until response.length) {
                when (response[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            jsonEnd = i
                            break
                        }
                    }
                }
            }

            if (jsonEnd == -1) {
                // Если не нашли баланс, ищем последнюю закрывающую скобку
                jsonEnd = response.lastIndexOf('}')
                if (jsonEnd <= jsonStart) return null
            }

            val json = response.substring(jsonStart, jsonEnd + 1)

            // Проверяем, что это похоже на наш JSON
            if (json.contains("\"coverage_assessment\"") ||
                json.contains("\"covered_aspects\"") ||
                json.contains("\"recommendations\"")) {

                // Исправляем распространенные ошибки
                return fixJsonErrors(json)
            }
        } catch (e: Exception) {
            // Игнорируем ошибки
        }

        return null
    }

    /**
     * Исправляет ошибки в JSON
     */
    private fun fixJsonErrors(json: String): String {
        var fixed = json

        // Убираем незавершенные строки
        fixed = fixed.replace(Regex("\"([^\"]*)\\.\\.\\.\""), "\"\$1\"")
        fixed = fixed.replace(Regex("\"([^\"]*)\\.\\.\""), "\"\$1\"")
        fixed = fixed.replace(Regex("\"([^\"]*)\\.\""), "\"\$1\"")

        // Убираем лишние запятые
        fixed = fixed.replace(Regex(",\\s*\\}"), "}")
        fixed = fixed.replace(Regex(",\\s*\\]"), "]")

        // Заменяем одинарные кавычки
        fixed = fixed.replace("'", "\"")

        return fixed
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
                            return fixJsonErrors(json)
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
        // Очищаем текст
        val cleanedText = cleanAIResponse(text)
        val lines = cleanedText.lines()

        // Ищем confidence в тексте
        val confidence = when {
            cleanedText.contains("высок", ignoreCase = true) ||
                    cleanedText.contains("high", ignoreCase = true) ||
                    cleanedText.contains("полное", ignoreCase = true) -> ConfidenceLevel.HIGH

            cleanedText.contains("средн", ignoreCase = true) ||
                    cleanedText.contains("medium", ignoreCase = true) ||
                    cleanedText.contains("частичное", ignoreCase = true) -> ConfidenceLevel.MEDIUM

            else -> ConfidenceLevel.LOW
        }

        // Извлекаем покрытые аспекты
        val coveredAspects = extractItemsFromText(cleanedText, listOf(
            "покрытые аспекты",
            "covered aspects",
            "что покрыто",
            "тесты проверяют"
        ))

        // Извлекаем непокрытые аспекты
        val missingAspects = extractItemsFromText(cleanedText, listOf(
            "непокрытые аспекты",
            "missing aspects",
            "что не покрыто",
            "нужно добавить"
        ))

        // Извлекаем рекомендации
        val recommendations = extractItemsFromText(cleanedText, listOf(
            "рекомендации",
            "recommendations",
            "советы",
            "что улучшить"
        ))

        // Формируем объяснение
        val explanation = buildExplanation(cleanedText)

        return AIAnalysis(
            rawText = cleanedText.take(500),
            confidence = confidence,
            coveredAspects = coveredAspects.ifEmpty {
                listOf("основная функциональность", "положительные сценарии")
            },
            missingAspects = missingAspects.ifEmpty {
                listOf("обработка ошибок", "пограничные случаи")
            },
            explanation = explanation,
            recommendations = recommendations.ifEmpty {
                listOf("добавить тесты на edge cases", "увеличить покрытие исключений")
            },
            coveredCriteria = emptyList(),
            missingCriteria = emptyList()
        )
    }

    /**
     * Извлекает элементы из текста
     */
    private fun extractItemsFromText(text: String, triggers: List<String>): List<String> {
        val items = mutableListOf<String>()

        for (trigger in triggers) {
            val index = text.lowercase().indexOf(trigger.lowercase())
            if (index != -1) {
                // Ищем после триггера
                var startPos = index + trigger.length
                var endPos = text.length

                // Ищем конец секции (по следующему триггеру или концу)
                for (otherTrigger in listOf("рекомендации", "recommendations", "непокрытые", "missing", "объяснение", "explanation")) {
                    if (otherTrigger != trigger) {
                        val otherIndex = text.lowercase().indexOf(otherTrigger.lowercase(), startPos)
                        if (otherIndex != -1 && otherIndex < endPos) {
                            endPos = otherIndex
                        }
                    }
                }

                val section = text.substring(startPos, endPos)

                // Ищем пункты в секции
                val lines = section.lines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("-") || trimmed.startsWith("•") || trimmed.startsWith("*")) {
                        val item = trimmed.substring(1).trim()
                        if (item.isNotBlank() && item.length < 100) {
                            items.add(item)
                        }
                    } else if (trimmed.matches("\\d+\\.\\s+.*".toRegex())) {
                        val item = trimmed.substring(trimmed.indexOf('.') + 1).trim()
                        if (item.isNotBlank() && item.length < 100) {
                            items.add(item)
                        }
                    }
                }

                break
            }
        }

        return items.take(5)
    }

    /**
     * Формирует объяснение из текста
     */
    private fun buildExplanation(text: String): String {
        // Ищем первое предложение или абзац
        val sentences = text.split(Regex("[.!?]"))
        if (sentences.isNotEmpty()) {
            val firstSentence = sentences[0].trim()
            if (firstSentence.isNotBlank() && firstSentence.length > 10) {
                return firstSentence.take(200)
            }
        }

        // Если не нашли предложение, берем первые 200 символов
        return text.take(200).trim()
    }

    private fun parseStructuredAIResponse(jsonText: String): AIAnalysis {
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val jsonNode = mapper.readTree(jsonText)

            val coverageAssessment = jsonNode.get("coverage_assessment")?.asText() ?: "partial"
            val confidenceText = jsonNode.get("confidence")?.asText() ?: "medium"

            // Получаем и очищаем объяснение
            val rawExplanation = jsonNode.get("explanation")?.asText() ?: "Анализ покрытия тестами"
            val explanation = cleanExplanation(rawExplanation)

            val confidence = when (confidenceText.lowercase()) {
                "high" -> ConfidenceLevel.HIGH
                "medium" -> ConfidenceLevel.MEDIUM
                "low" -> ConfidenceLevel.LOW
                else -> ConfidenceLevel.MEDIUM
            }

            // Извлекаем и очищаем массивы
            val coveredAspects = extractAndCleanJsonArray(jsonNode, "covered_aspects")
                .ifEmpty { listOf("основная функциональность", "положительные сценарии") }

            val missingAspects = extractAndCleanJsonArray(jsonNode, "missing_aspects")
                .ifEmpty { listOf("обработка ошибок", "пограничные случаи") }

            val recommendations = extractAndCleanJsonArray(jsonNode, "recommendations")
                .ifEmpty { listOf("добавить тесты на edge cases", "увеличить покрытие исключений") }

            val coveredCriteria = extractAndCleanJsonArray(jsonNode, "covered_criteria")
            val missingCriteria = extractAndCleanJsonArray(jsonNode, "missing_criteria")

            AIAnalysis(
                rawText = jsonText.take(500),
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
            // Fallback на текстовый парсинг
            parseEnhancedTextAIResponse(jsonText)
        }
    }

    /**
     * Очищает объяснение от некорректных символов
     */
    private fun cleanExplanation(explanation: String): String {
        return explanation
            .replace("...", ".")
            .replace("\\.\\.\\.", ".")
            .replace("..", ".")
            .replace(Regex("\\s+"), " ")
            .take(200)
            .trim()
    }

    /**
     * Извлекает и очищает массив из JsonNode
     */
    private fun extractAndCleanJsonArray(jsonNode: com.fasterxml.jackson.databind.JsonNode, fieldName: String): List<String> {
        return try {
            val arrayNode = jsonNode.get(fieldName)
            if (arrayNode != null && arrayNode.isArray) {
                arrayNode.mapNotNull {
                    val text = it.asText()
                    if (text.isNotBlank()) {
                        text.trim()
                            .replace("...", "")
                            .replace("\\.\\.\\.", "")
                            .replace("..", "")
                            .replace(Regex("\\s+"), " ")
                            .take(100)
                    } else {
                        null
                    }
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Создает fallback анализ
     */
    private fun createFallbackAnalysis(response: String): AIAnalysis {
        val cleanedResponse = cleanAIResponse(response)

        return AIAnalysis(
            rawText = cleanedResponse.take(500),
            confidence = ConfidenceLevel.LOW,
            coveredAspects = listOf("основная функциональность"),
            missingAspects = listOf("обработка ошибок", "edge cases"),
            explanation = "Не удалось получить структурированный анализ. Ответ AI не соответствует ожидаемому формату.",
            recommendations = listOf(
                "Проверьте настройки AI сервиса",
                "Упростите промпт для получения структурированного ответа"
            ),
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
        }

        // Топ требований по покрытию
        val top5 = reports.sortedByDescending { it.coverageScore }.take(5)
        println("\n🏆 Топ-5 требований по покрытию:")
        top5.forEachIndexed { index, report ->
            println("  ${index + 1}. ${report.requirement.title} - ${"%.1f".format(report.coverageScore)}% (${report.matches.size} тестов)")
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