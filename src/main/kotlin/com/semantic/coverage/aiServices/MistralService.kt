package com.semantic.coverage.aiServices

import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Сервис для работы с Mistral AI через REST API
 */
class MistralService(
    private val apiKey: String,
    private val model: String = "mistral-medium",
    private val baseUrl: String = "https://api.mistral.ai/v1"
) : AiService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    override fun analyze(prompt: String): String {
        return try {
            println("🤖 Запрос к Mistral AI (модель: $model)...")

            val requestBody = createChatRequest(prompt).toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Empty error body"
                println("❌ Ошибка Mistral API: ${response.code} - $errorBody")
                return "Ошибка Mistral API ${response.code}: ${response.message}. Подробности: $errorBody"
            }

            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Пустой ответ от Mistral API")

            val result = parseMistralResponse(responseBody)
            println("✅ Ответ от Mistral получен (${result.length} символов)")
            result

        } catch (e: Exception) {
            println("❌ Ошибка при вызове Mistral API: ${e.message}")
            e.printStackTrace()
            "Ошибка при анализе с Mistral AI: ${e.message}"
        }
    }

    override fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            Ты - старший QA инженер с 10+ лет опыта. Проведи детальный анализ покрытия тестами.
            
            ## ИНСТРУКЦИЯ:
            1. Проанализируй, насколько тестовый код покрывает бизнес-требование
            2. Будь максимально конкретным - ссылайся на строки кода, названия методов
            3. Оцени покрытие критериев приемки (если они есть в требовании)
            4. Используй русский язык для ответа
            5. Форматируй ответ в Markdown
            
            ## БИЗНЕС-ТРЕБОВАНИЕ:
            ```
            $requirement
            ```
            
            ## ТЕСТОВЫЙ КОД:
            ```kotlin
            $testCode
            ```
            
            ## ФОРМАТ ОТВЕТА:
            ### 📊 Оценка покрытия:
            - **Полное/Частичное/Отсутствует** (с объяснением почему)
            - **Уровень уверенности**: Высокий/Средний/Низкий
            
            ### ✅ Покрытые аспекты:
            - Конкретный аспект 1 (ссылка на тест: строка X, метод Y)
            - Конкретный аспект 2 (ссылка на тест: ...)
            
            ### ⚠️ Непокрытые аспекты:
            - Аспект 1 (что нужно добавить)
            - Аспект 2 (что нужно добавить)
            
            ### 🔍 Анализ критериев приемки:
            - Покрытые критерии: ...
            - Непокрытые критерии: ...
            
            ### 💡 Рекомендации по улучшению:
            1. Рекомендация 1 (конкретная)
            2. Рекомендация 2 (конкретная)
            
            ### 🎯 Итоговая оценка покрытия: X%
        """.trimIndent()

        return analyze(prompt)
    }

    /**
     * Проверяет доступность API ключа
     */
    fun testApiKey(): Boolean {
        println("🔑 Проверка API ключа Mistral AI...")

        return try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val isSuccessful = response.isSuccessful

            if (isSuccessful) {
                println("✅ API ключ Mistral AI рабочий")
            } else {
                println("❌ API ключ Mistral AI не работает: ${response.code}")
            }

            response.close()
            isSuccessful
        } catch (e: Exception) {
            println("❌ Ошибка при проверке API ключа Mistral: ${e.message}")
            false
        }
    }

    /**
     * Получает список доступных моделей
     */
    fun getAvailableModels(): List<String> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return emptyList()
            }

            val responseBody = response.body?.string()
                ?: return emptyList()

            val json = gson.fromJson(responseBody, Map::class.java)
            val data = json["data"] as? List<*>

            data?.mapNotNull { model ->
                (model as? Map<*, *>)?.get("id") as? String
            } ?: emptyList()

        } catch (e: Exception) {
            println("⚠️ Не удалось получить список моделей: ${e.message}")
            emptyList()
        }
    }

    /**
     * Создает запрос для чата
     */
    private fun createChatRequest(prompt: String): String {
        val messages = listOf(
            mapOf(
                "role" to "system",
                "content" to "Ты - опытный QA инженер, специализирующийся на анализе покрытия тестами. " +
                        "Отвечай только на русском языке. Будь максимально конкретным и технически точным. " +
                        "Ссылайся на конкретные строки кода, методы и проверки. " +
                        "Используй Markdown для форматирования ответа."
            ),
            mapOf(
                "role" to "user",
                "content" to prompt
            )
        )

        val requestBody = mapOf(
            "model" to model,
            "messages" to messages,
            "temperature" to 0.1,
            "max_tokens" to 2000,
            "top_p" to 0.9
        )

        return gson.toJson(requestBody)
    }

    /**
     * Парсит ответ от Mistral API
     */
    private fun parseMistralResponse(jsonResponse: String): String {
        return try {
            val json = gson.fromJson(jsonResponse, Map::class.java)
            val choices = json["choices"] as? List<*>
            val firstChoice = choices?.firstOrNull() as? Map<*, *>
            val message = firstChoice?.get("message") as? Map<*, *>
            val content = message?.get("content") as? String

            content ?: "Ответ не содержит контента"
        } catch (e: Exception) {
            println("⚠️ Ошибка парсинга ответа Mistral: ${e.message}")
            "Ошибка при обработке ответа от Mistral AI"
        }
    }

    /**
     * Анализ покрытия с расширенным контекстом
     */
    fun analyzeCoverageWithContext(
        requirement: String,
        testCode: String,
        context: Map<String, String> = emptyMap()
    ): String {
        val contextText = if (context.isNotEmpty()) {
            "\n## КОНТЕКСТ АНАЛИЗА:\n" + context.entries.joinToString("\n") {
                "- **${it.key}**: ${it.value}"
            }
        } else ""

        val prompt = """
            Ты проводишь экспертный анализ тестового покрытия. 
            Учти весь предоставленный контекст.
            
            ## БИЗНЕС-ТРЕБОВАНИЕ:
            ```
            $requirement
            ```
            
            ## ТЕСТОВЫЙ КОД:
            ```kotlin
            $testCode
            ```
            $contextText
            
            ## ЗАДАЧА:
            1. Проведи детальный семантический анализ соответствия теста требованию
            2. Оцени полноту покрытия всех аспектов требования
            3. Проверь корректность тестовых проверок (assertions)
            4. Предложи улучшения для edge cases
            5. Оцени риски недостаточного покрытия
            
            Формат ответа - Markdown с четкой структурой.
        """.trimIndent()

        return analyze(prompt)
    }
}

/**
 * Data class для запроса к Mistral API
 */
data class MistralChatRequest(
    val model: String,
    val messages: List<MistralMessage>,
    val temperature: Double = 0.1,
    val maxTokens: Int = 2000,
    val topP: Double = 0.9,
    val stream: Boolean = false
)

/**
 * Data class для сообщения в Mistral API
 */
data class MistralMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

/**
 * Data class для ответа от Mistral API
 */
data class MistralChatResponse(
    val id: String,
    val objectType: String,
    val created: Long,
    val model: String,
    val choices: List<MistralChoice>,
    val usage: MistralUsage?
)

data class MistralChoice(
    val index: Int,
    val message: MistralMessage,
    val finishReason: String?
)

data class MistralUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)