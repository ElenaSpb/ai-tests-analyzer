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

/**
 * Сервис эмбеддингов для Mistral AI
 * Поддерживает модели:
 * - mistral-embed (1024 измерения)
 * - codestral-embed (для кода, 1024 измерения)
 */
class MistralEmbeddingService(
    private val apiKey: String,
    private val textModel: String = "mistral-embed",
    private val codeModel: String = "codestral-embed", //"codestral-2501",
    private val baseUrl: String = "https://api.mistral.ai/v1",
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

    // Определяем размеры векторов в зависимости от модели
    private val dimensions: Int = when (textModel.lowercase()) {
        "mistral-embed" -> 1024
        "codestral-2501" -> 1024
        else -> 1024 // дефолтное значение для Mistral моделей
    }

    // Data class для запроса к Mistral Embeddings API
    data class MistralEmbeddingRequest(
        val model: String,
        val input: List<String>,
        @JsonProperty("encoding_format")
        val encodingFormat: String = "float"
    )

    // Data class для ответа Mistral Embeddings API
    data class MistralEmbeddingResponse(
        val id: String,
        val `object`: String,
        val data: List<EmbeddingData>,
        val model: String,
        val usage: UsageData
    )

    data class EmbeddingData(
        val `object`: String,
        val index: Int,
        val embedding: List<Float>
    )

    data class UsageData(
        @JsonProperty("prompt_tokens")
        val promptTokens: Int,
        @JsonProperty("total_tokens")
        val totalTokens: Int
    )

    // Кэш для эмбеддингов
    private val embeddingTextCache = mutableMapOf<String, FloatArray>()
    private val embeddingCodeCache = mutableMapOf<String, FloatArray>()

    override fun getTextEmbedding(text: String): FloatArray {
        return embeddingTextCache.getOrPut(text) {
            try {
                val embeddings = getEmbeddingsBatch(listOf(text), textModel)
                embeddings.firstOrNull() ?: FloatArray(dimensions) { 0f }
            } catch (e: Exception) {
                println("⚠️  Ошибка получения эмбеддинга Mistral: ${e.message}")
                // Fallback на простой эмбеддинг
                createFallbackEmbedding(text)
            }
        }
    }

    /**
     * Специальный метод для получения эмбеддингов кода
     * Использует модель codestral-2501 для лучшего представления кода
     */
    override fun getCodeEmbedding(code: String): FloatArray {
        println("Внимание: для лучшего представления кода используется модель $codeModel")
        val cacheKey = "code_${code.hashCode()}"
        return embeddingCodeCache.getOrPut(cacheKey) {
            try {
                // Для кода можем добавить контекст
                val enrichedCode = """
                    // Код для анализа покрытия тестами
                    $code
                    
                    // Контекст: тестовый код, проверки, assertions
                """.trimIndent()

                val embeddings = getEmbeddingsBatch(listOf(enrichedCode),  codeModel)
                embeddings.firstOrNull() ?: FloatArray(dimensions) { 0f }
            } catch (e: Exception) {
                println("⚠️  Ошибка получения эмбеддинга кода: ${e.message}")
                createFallbackEmbedding(code)
            }
        }
    }

    /**
     * Получает эмбеддинг для тестового требования
     * Оптимизирован для текста требований
     */
    fun getRequirementEmbedding(requirement: String): FloatArray {
        val cacheKey = "req_${requirement.hashCode()}"
        return embeddingTextCache.getOrPut(cacheKey) {
            try {
                // Структурируем требование для лучшего представления
                val structuredReq = """
                    Бизнес-требование для анализа тестового покрытия:
                    
                    Текст требования:
                    $requirement
                    
                    Контекст: покрытие тестами, критерии приемки, проверка корректности
                """.trimIndent()

                val embeddings = getEmbeddingsBatch(listOf(structuredReq), textModel)
                embeddings.firstOrNull() ?: FloatArray(dimensions) { 0f }
            } catch (e: Exception) {
                println("⚠️  Ошибка получения эмбеддинга требования: ${e.message}")
                createFallbackEmbedding(requirement)
            }
        }
    }

    private fun createFallbackEmbedding(text: String): FloatArray {
        // Улучшенный fallback алгоритм для кода и текста
        val embedding = FloatArray(dimensions) { 0f }
        val tokens = if (textModel == codeModel) {
            // Для кода: разбиваем на токены учитывая синтаксис
            text.split(Regex("\\s+|(?<=[{}();.,])|(?=[{}();.,])"))
        } else {
            // Для текста: обычное разбиение на слова
            text.lowercase().split(Regex("\\s+|(?=[.,!?;:])"))
        }

        tokens.forEachIndexed { tokenIndex, token ->
            if (token.isNotBlank()) {
                val hash = token.hashCode()
                // Распределяем по нескольким измерениям
                for (i in 0 until minOf(8, dimensions)) {
                    val idx = ((hash + i * 997) % dimensions).absoluteValue // Простое число для лучшего распределения
                    val weight = 1.0f / (tokenIndex + 1) * (1.0f - i * 0.1f)

                    // Учитываем длину токена и содержание
                    val tokenWeight = when {
                        token.length > 5 -> 1.2f // Длинные токены более значимы
                        token.matches(Regex("[A-Z_][A-Z0-9_]+")) -> 1.5f // Константы в коде
                        token.matches(Regex("[a-z][A-Za-z0-9]*")) -> 1.1f // Идентификаторы
                        else -> 1.0f
                    }

                    embedding[idx] += weight * tokenWeight
                }
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

    private fun getEmbeddingsBatch(texts: List<String>, modelForEmbedding: String): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val request = MistralEmbeddingRequest(
            model = modelForEmbedding,
            input = texts,
            encodingFormat = "float"
        )

        val json = mapper.writeValueAsString(request)

        val httpRequest = Request.Builder()
            .url("$baseUrl/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw Exception("Пустой ответ от Mistral API")

        if (!response.isSuccessful) {
            println("❌ Ошибка Mistral Embeddings API: ${response.code}")
            println("Ответ: $responseBody")
            throw Exception("Ошибка Mistral API ${response.code}: ${response.message}")
        }

        return try {
            parseMistralEmbeddingsResponse(responseBody)
        } catch (e: Exception) {
            println("⚠️  Ошибка парсинга JSON: ${e.message}")
            // Альтернативный парсинг
            parseEmbeddingsManually(responseBody, texts.size)
        }
    }

    private fun parseMistralEmbeddingsResponse(json: String): List<FloatArray> {
        return try {
            val response = mapper.readValue(json, MistralEmbeddingResponse::class.java)
            response.data.map { embeddingData ->
                FloatArray(embeddingData.embedding.size) { i ->
                    embeddingData.embedding[i]
                }
            }
        } catch (e: Exception) {
            // Fallback: ручной парсинг
            val root = mapper.readTree(json)
            val dataArray = root.get("data")

            if (!dataArray.isArray) {
                throw Exception("Неверный формат ответа: нет массива data")
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
            embeddings
        }
    }

    private fun parseEmbeddingsManually(json: String, expectedCount: Int): List<FloatArray> {
        // Ручной парсинг JSON для надежности
        val embeddings = mutableListOf<FloatArray>()

        // Ищем все массивы embedding
        val embeddingPattern = "\"embedding\"\\s*:\\s*\\[([^\\]]+)\\]".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = embeddingPattern.findAll(json)

        matches.forEach { match ->
            val numbers = match.groupValues[1]
                .split(",")
                .map { it.trim().toFloatOrNull() ?: 0f }
                .toFloatArray()

            if (numbers.isNotEmpty()) {
                embeddings.add(numbers)
            }
        }

        if (embeddings.size != expectedCount) {
            println("⚠️  Ожидалось $expectedCount эмбеддингов, получено ${embeddings.size}")
            // Дополняем если нужно
            while (embeddings.size < expectedCount) {
                embeddings.add(FloatArray(dimensions) { 0f })
            }
        }

        return embeddings
    }

    override fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) {
            println("⚠️  Размеры векторов не совпадают: ${vec1.size} != ${vec2.size}")
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
        return if (denominator > 0) {
            val similarity = (dot / denominator).toFloat()
            // Ограничиваем значения
            similarity.coerceIn(-1.0f, 1.0f)
        } else {
            0.0f
        }
    }

    /**
     * Улучшенное сходство с учетом типов данных
     */
    fun enhancedSimilarity(
        requirementEmbedding: FloatArray,
        testEmbedding: FloatArray,
        requirementType: String = "text",
        testType: String = "code"
    ): Float {
        val baseSimilarity = cosineSimilarity(requirementEmbedding, testEmbedding)

        // Дополнительные веса в зависимости от типов
        return when {
            requirementType == "code" && testType == "code" -> {
                // Код-код сравнение
                baseSimilarity * 1.1f
            }

            requirementType == "text" && testType == "code" -> {
                // Текст-код сравнение (обычный сценарий)
                baseSimilarity
            }

            else -> baseSimilarity
        }.coerceIn(0.0f, 1.0f)
    }

    /**
     * Пакетное сравнение сходства
     */
    fun batchSimilarity(
        requirementEmbedding: FloatArray,
        testEmbeddings: List<FloatArray>
    ): List<Float> {
        return testEmbeddings.map { testEmbedding ->
            cosineSimilarity(requirementEmbedding, testEmbedding)
        }
    }

    /**
     * Находит наиболее похожие тесты
     */
    fun findTopSimilarTests(
        requirementEmbedding: FloatArray,
        testEmbeddings: List<Pair<String, FloatArray>>, // (testId, embedding)
        topK: Int = 10,
        threshold: Float = 0.3f
    ): List<Pair<String, Float>> {
        return testEmbeddings
            .map { (testId, embedding) ->
                testId to cosineSimilarity(requirementEmbedding, embedding)
            }
            .filter { (_, similarity) -> similarity >= threshold }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(topK)
    }

    private fun createUnsafeClient(): OkHttpClient {
        println("⚠️  ВНИМАНИЕ: Используется небезопасная SSL конфигурация")

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
            println("⚠️  Не удалось создать небезопасный клиент: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Проверяет доступность API
     */
    fun testApiKey(): Boolean {
        println("🔑 Проверка API ключа Mistral...")

        return try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val isSuccessful = response.isSuccessful

            if (isSuccessful) {
                println("✅ API ключ Mistral рабочий")
            } else {
                println("❌ API ключ Mistral не работает: ${response.code}")
            }

            response.close()
            isSuccessful
        } catch (e: Exception) {
            println("❌ Ошибка при проверке API ключа Mistral: ${e.message}")
            false
        }
    }

    /**
     * Получает информацию о модели
     */
    fun getModelInfo(): String {
        return when (textModel.lowercase()) {
            "mistral-embed" -> "Mistral Embed (1024 dim) - для общего текста"
            "codestral-2501" -> "Codestral-2501 (1024 dim) - оптимизирована для кода"
            else -> "Неизвестная модель: $textModel"
        }
    }

    fun clearCache() {
        embeddingTextCache.clear()
        println("🧹 Кэш эмбеддингов очищен")
    }

    fun getCacheSize(): Int = embeddingTextCache.size

    fun getCacheStats(): String {
        val totalVectors = embeddingTextCache.size
        val totalMemory = totalVectors * dimensions * 4 / 1024.0 / 1024.0 // MB
        return "Кэш: $totalVectors векторов, ~${"%.2f".format(totalMemory)} MB"
    }

    fun getServiceInfo(): String {
        return "MistralEmbeddingService(model=$textModel, dim=$dimensions, url=$baseUrl)"
    }
}

/**
 * Фабрика для создания сервисов эмбеддингов Mistral
 */
object MistralEmbeddingFactory {

    /**
     * Создает сервис для общего текста
     */
    fun createTextEmbeddingService(apiKey: String): MistralEmbeddingService {
        return MistralEmbeddingService(
            apiKey = apiKey,
            textModel = "mistral-embed",
            baseUrl = "https://api.mistral.ai/v1"
        )
    }

    /**
     * Создает сервис для кода (оптимизирован)
     */
    fun createCodeEmbeddingService(apiKey: String): MistralEmbeddingService {
        return MistralEmbeddingService(
            apiKey = apiKey,
            textModel = "codestral-2501",
            baseUrl = "https://api.mistral.ai/v1"
        )
    }

    /**
     * Создает двойной сервис для гибридного анализа
     */
    fun createHybridService(apiKey: String): HybridEmbeddingService {
        return HybridEmbeddingService(
            textService = createTextEmbeddingService(apiKey),
            codeService = createCodeEmbeddingService(apiKey)
        )
    }
}

/**
 * Гибридный сервис для анализа текста и кода
 */
class HybridEmbeddingService(
    private val textService: MistralEmbeddingService,
    private val codeService: MistralEmbeddingService
) : EmbeddingService {

    override fun getCodeEmbedding(code: String): FloatArray = codeService.getTextEmbedding(code)

    override fun getTextEmbedding(text: String): FloatArray {
        // По умолчанию используем текстовый сервис
        return textService.getTextEmbedding(text)
    }

    /**
     * Получает комбинированный эмбеддинг для лучшего сравнения текст-код
     */
    fun getHybridEmbeddingForComparison(text: String): Pair<FloatArray, FloatArray> {
        val textEmbedding = textService.getTextEmbedding(text)
        val codeEmbedding = codeService.getTextEmbedding(text) // Для кросмодального сравнения
        return textEmbedding to codeEmbedding
    }

    /**
     * Сравнивает требование с тестом используя оба представления
     */
    fun compareRequirementWithTest(
        requirementText: String,
        testCode: String
    ): HybridComparisonResult {
        val requirementTextEmbedding = textService.getRequirementEmbedding(requirementText)
        val requirementCodeEmbedding = codeService.getRequirementEmbedding(requirementText)
        val testCodeEmbedding = codeService.getCodeEmbedding(testCode)
        val testTextEmbedding = textService.getTextEmbedding(testCode)

        val similarities = listOf(
            textService.cosineSimilarity(requirementTextEmbedding, testTextEmbedding),
            textService.cosineSimilarity(requirementTextEmbedding, testCodeEmbedding),
            codeService.cosineSimilarity(requirementCodeEmbedding, testCodeEmbedding),
            codeService.cosineSimilarity(requirementCodeEmbedding, testTextEmbedding)
        )

        val avgSimilarity = similarities.average().toFloat()
        val maxSimilarity = similarities.maxOrNull() ?: 0f

        return HybridComparisonResult(
            averageSimilarity = avgSimilarity,
            maxSimilarity = maxSimilarity,
            individualSimilarities = similarities,
            recommendation = when {
                avgSimilarity > 0.7 -> "Высокое сходство"
                avgSimilarity > 0.4 -> "Умеренное сходство"
                else -> "Низкое сходство"
            }
        )
    }

    override fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        // Используем текстовый сервис по умолчанию
        return textService.cosineSimilarity(vec1, vec2)
    }
}

data class HybridComparisonResult(
    val averageSimilarity: Float,
    val maxSimilarity: Float,
    val individualSimilarities: List<Float>,
    val recommendation: String
)