package com.semantic.coverage.embedding

import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.absoluteValue
import kotlin.math.sqrt

class OpenAIEmbeddingService(
    private val apiKey: String,
    private val model: String = "text-embedding-3-large", // or "text-embedding-3-small"
    private val dimensions: Int = 3072, //1536 for small, 3072 for large
    private val unsafeSSL: Boolean = false
) : EmbeddingService {

    private val client = if (unsafeSSL) {
        createUnsafeClient()
    } else {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json".toMediaType()
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper().apply {
        registerModule(com.fasterxml.jackson.module.kotlin.kotlinModule())
    }

    // Простые data class без @JsonCreator
    data class OpenAIEmbeddingRequest(
        val model: String,
        val input: List<String>,
        @JsonProperty("encoding_format") val encodingFormat: String = "float",
        val dimensions: Int? = null
    )

    // Кэш для эмбеддингов
    private val embeddingCache = mutableMapOf<String, FloatArray>()

    override fun getTextEmbedding(text: String): FloatArray {
        return embeddingCache.getOrPut(text) {
            try {
                val embeddings = getEmbeddingsBatch(listOf(text))
                embeddings.firstOrNull() ?: FloatArray(dimensions) { 0f }
            } catch (e: Exception) {
                println("⚠️  Failed to get OpenAI embedding: ${e.message}")
                // Fallback на простой эмбеддинг
                createFallbackEmbedding(text)
            }
        }
    }

    private fun createFallbackEmbedding(text: String): FloatArray {
        // Простой fallback алгоритм
        val embedding = FloatArray(dimensions) { 0f }
        val words = text.lowercase().split("\\s+".toRegex())

        words.forEachIndexed { wordIndex, word ->
            val hash = word.hashCode()
            for (i in 0 until minOf(10, dimensions)) {
                val idx = ((hash + i * 31) % dimensions).absoluteValue
                embedding[idx] += 1.0f / (wordIndex + 1) / (i + 1)
            }
        }
        return normalize(embedding)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val sum = vector.fold(0.0) { acc, v -> acc + v * v }
        val norm = sqrt(sum).toFloat()
        return if (norm > 0) {
            FloatArray(vector.size) { i -> vector[i] / norm }
        } else {
            vector
        }
    }

    private fun getEmbeddingsBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val request = OpenAIEmbeddingRequest(
            model = model,
            input = texts,
            dimensions = dimensions
        )

        val json = mapper.writeValueAsString(request)

        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            println("OpenAI API error: ${response.code}")
            println("Response: $responseBody")
            throw Exception("OpenAI API error ${response.code}: ${response.message}")
        }

        return try {
            // Простой парсинг JSON без сложных структур
            parseEmbeddingsResponse(responseBody)
        } catch (e: Exception) {
            println("JSON parsing error: ${e.message}")
            // Альтернативный парсинг
            parseEmbeddingsManually(responseBody, texts.size)
        }
    }

    private fun parseEmbeddingsResponse(json: String): List<FloatArray> {
        // Простой парсинг с использованием JsonNode
        val root = mapper.readTree(json)
        val dataArray = root.get("data")

        if (!dataArray.isArray) {
            throw Exception("Invalid response format: no data array")
        }

        val embeddings = mutableListOf<FloatArray>()

        for (item in dataArray) {
            val embeddingNode = item.get("embedding")
            if (embeddingNode.isArray) {
                val embedding = FloatArray(embeddingNode.size())
                for (i in 0 until embeddingNode.size()) {
                    embedding[i] = embeddingNode[i].asDouble().toFloat()
                }
                embeddings.add(embedding)
            }
        }
        return embeddings
    }

    private fun parseEmbeddingsManually(json: String, expectedCount: Int): List<FloatArray> {
        // Ручной парсинг JSON
        val embeddings = mutableListOf<FloatArray>()

        // Ищем все массивы embedding
        val embeddingPattern = "\"embedding\":\\[([^\\]]+)\\]".toRegex()
        val matches = embeddingPattern.findAll(json)

        matches.forEach { match ->
            val numbers = match.groupValues[1]
                .split(",")
                .map { it.trim().toFloatOrNull() ?: 0f }
                .toFloatArray()

            if (numbers.size == dimensions || numbers.size > 0)
                embeddings.add(numbers)

        }

        if (embeddings.size != expectedCount) {
            println("⚠️  Expected $expectedCount embeddings, got ${embeddings.size}")
            // Дополняем если нужно
            while (embeddings.size < expectedCount) {
                embeddings.add(FloatArray(dimensions) { 0f })
            }
        }
        return embeddings
    }

    override fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) {
            return 0.0f
        }

        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in vec1.indices) {
            dot += vec1[i].toDouble() * vec2[i].toDouble()
            norm1 += vec1[i].toDouble() * vec1[i].toDouble()
            norm2 += vec2[i].toDouble() * vec2[i].toDouble()
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0)
            (dot / denominator).toFloat()
        else
            0.0f
    }

    private fun createUnsafeClient(): OkHttpClient {
        println(" WARNING: Using unsafe SSL configuration")

        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) = Unit

                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            })

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            println("⚠️  Failed to create unsafe client: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    fun clearCache() {
        embeddingCache.clear()
    }

    fun getCacheSize(): Int = embeddingCache.size

    fun getServiceInfo(): String = "OpenAIEmbeddingService($model, dim=$dimensions)"
}