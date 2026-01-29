package com.semantic.coverage.embedding

import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.sqrt

class LocalEmbeddingService : EmbeddingService {
    // Используем MiniLM модель через TensorFlow или локальную версию
    // Для прототипа можно использовать упрощенный TF Hub-подобный подход
    private val dimension = 384 // Размерность для MiniLM

    // Кэш для уже вычисленных эмбеддингов
    private val embeddingCache = mutableMapOf<String, FloatArray>()

    override fun getEmbedding(text: String): FloatArray {
        return embeddingCache.getOrPut(text) {
            // В реальной реализации здесь будет вызов TensorFlow модели
            // Для прототипа используем упрощенный алгоритм
            computeSimpleEmbedding(text)
        }
    }

    private fun computeSimpleEmbedding(text: String): FloatArray {
        // Упрощенная реализация для демонстрации
        // В реальном прототипе нужно загрузить предобученную модель
        val words = text.lowercase(Locale.getDefault())
            .split("\\s+".toRegex())
            .filter { it.length > 2 }

        val embedding = FloatArray(dimension) { 0f }

        // Примитивное хеширование слов в вектор
        words.forEach { word ->
            val hash = word.hashCode()
            for (i in 0 until minOf(dimension, 10)) {
                val index = ((hash + i * 31) % dimension).absoluteValue
                embedding[index] += 1.0f / (i + 1)
            }
        }

        // Нормализация
        val sumOfSquares = embedding.fold(0.0) { acc, value ->
            acc + value * value
        }
        val norm = sqrt(sumOfSquares).toFloat()
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    override fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must have same dimension" }

        var dotProduct = 0.0f
        var norm1 = 0.0f
        var norm2 = 0.0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (sqrt(norm1.toDouble()) * sqrt(norm2.toDouble())).toFloat()
        } else {
            0.0f
        }
    }
}