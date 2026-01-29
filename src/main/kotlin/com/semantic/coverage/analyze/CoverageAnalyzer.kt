package com.semantic.coverage.analyze

import com.semantic.coverage.dto.ConfidenceLevel
import com.semantic.coverage.embedding.LocalEmbeddingService
import com.semantic.coverage.dto.MatchResult
import com.semantic.coverage.dto.TestChunk
import com.semantic.coverage.dto.Requirement
import java.util.*

class CoverageAnalyzer(
    private val embeddingService: LocalEmbeddingService,
    private val similarityThreshold: Float = 0.4f
) {

    fun analyzeCoverage(
        requirements: List<Requirement>,
        testChunks: List<TestChunk>
    ): List<CoverageReport> {
        // Векторизуем требования
        val requirementsWithEmbeddings = requirements.map { req ->
            req.copy(embedding = embeddingService.getEmbedding("${req.title} ${req.description}"))
        }

        // Векторизуем тесты (при необходимости)
        val testChunksWithEmbeddings = testChunks.map { chunk ->
            if (chunk.embedding == null) {
                val enrichedContent = "${chunk.testName} ${chunk.content} ${chunk.metadata.values.joinToString(" ")}"
                chunk.copy(embedding = embeddingService.getEmbedding(enrichedContent))
            } else {
                chunk
            }
        }

        // Для каждого требования ищем соответствия
        return requirementsWithEmbeddings.map { requirement ->
            val matches = findMatchesForRequirement(requirement, testChunksWithEmbeddings)
            val coverageScore = calculateCoverageScore(matches)
            val confidence = calculateConfidenceLevel(matches)
            val gaps = identifyGaps(requirement, matches)

            CoverageReport(
                requirement = requirement,
                matches = matches,
                coverageScore = coverageScore,
                confidence = confidence,
                gaps = gaps
            )
        }
    }

    private fun findMatchesForRequirement(
        requirement: Requirement,
        testChunks: List<TestChunk>,
        topK: Int = 10
    ): List<MatchResult> {
        require(requirement.embedding != null) { "Requirement must have embedding" }

        return testChunks
            .filter { it.embedding != null }
            .map { chunk ->
                val similarity = embeddingService.cosineSimilarity(
                    requirement.embedding,
                    chunk.embedding!!
                )
                val confidence = when {
                    similarity > 0.7 -> ConfidenceLevel.HIGH
                    similarity > 0.4 -> ConfidenceLevel.MEDIUM
                    else -> ConfidenceLevel.LOW
                }

                val evidence = generateEvidence(requirement, chunk, similarity)

                MatchResult(
                    requirement = requirement,
                    testChunk = chunk,
                    similarityScore = similarity,
                    evidence = evidence,
                    confidence = confidence
                )
            }
            .filter { it.similarityScore >= similarityThreshold }
            .sortedByDescending { it.similarityScore }
            .take(topK)
    }

    private fun calculateCoverageScore(matches: List<MatchResult>): Float {
        if (matches.isEmpty()) return 0.0f

        val highConfidenceMatches = matches.filter { it.confidence == ConfidenceLevel.HIGH }
        val mediumConfidenceMatches = matches.filter { it.confidence == ConfidenceLevel.MEDIUM }

        // Весовая формула: HIGH * 1.0 + MEDIUM * 0.5
        val weightedScore = (highConfidenceMatches.size * 1.0f +
                mediumConfidenceMatches.size * 0.5f) /
                (matches.size * 1.0f)

        // Учитываем максимальное similarity
        val maxSimilarity = matches.maxOfOrNull { it.similarityScore } ?: 0.0f

        return (weightedScore * 0.6f + maxSimilarity * 0.4f) * 100f
    }

    private fun calculateConfidenceLevel(matches: List<MatchResult>): ConfidenceLevel {
        if (matches.isEmpty()) return ConfidenceLevel.LOW

        val highCount = matches.count { it.confidence == ConfidenceLevel.HIGH }
        val mediumCount = matches.count { it.confidence == ConfidenceLevel.MEDIUM }

        return when {
            highCount >= 2 -> ConfidenceLevel.HIGH
            highCount + mediumCount >= 3 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    private fun identifyGaps(requirement: Requirement, matches: List<MatchResult>): List<String> {
        val gaps = mutableListOf<String>()

        // Анализ на основе ключевых слов в требовании
        val keywords = extractKeywords(requirement.description)
        val matchedKeywords = mutableSetOf<String>()

        matches.forEach { match ->
            keywords.forEach { keyword ->
                if (match.testChunk.content.contains(keyword, ignoreCase = true) ||
                    match.testChunk.testName.contains(keyword, ignoreCase = true)) {
                    matchedKeywords.add(keyword)
                }
            }
        }

        // Ищем непокрытые ключевые слова
        val unmatchedKeywords = keywords - matchedKeywords
        if (unmatchedKeywords.isNotEmpty()) {
            gaps.add("Не найдены тесты для ключевых понятий: ${unmatchedKeywords.joinToString(", ")}")
        }

        // Проверяем уровень покрытия
        if (matches.isEmpty()) {
            gaps.add("Требование не имеет ни одного связанного теста")
        } else if (matches.none { it.confidence == ConfidenceLevel.HIGH }) {
            gaps.add("Отсутствуют тесты с высокой степенью уверенности в покрытии")
        }

        return gaps
    }

    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by")
        return text.lowercase(Locale.getDefault())
            .split("\\W+".toRegex())
            .filter { it.length > 3 && it !in stopWords && !it.matches("\\d+".toRegex()) }
            .toSet()
    }

    private fun generateEvidence(requirement: Requirement, testChunk: TestChunk, similarity: Float): String {
        return buildString {
            append("Тест '${testChunk.testName}' (файл: ${testChunk.filePath})\n")
            append("Сходство: ${"%.2f".format(similarity)}\n")
            append("Фрагмент кода: ${testChunk.content.take(150)}...\n")

            // Простой анализ ключевых слов
            val reqKeywords = extractKeywords(requirement.description)
            val testKeywords = extractKeywords("${testChunk.testName} ${testChunk.content}")
            val commonKeywords = reqKeywords.intersect(testKeywords)

            if (commonKeywords.isNotEmpty()) {
                append("Общие ключевые слова: ${commonKeywords.joinToString(", ")}")
            }
        }
    }
}