// OpenAIService.kt (реализация AIService)
package com.semantic.coverage.`ai-services`

import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiService(private val apiKey: String) : AiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    data class OpenAIRequest(
        val model: String = "gpt-3.5-turbo",
        val messages: List<Message>,
        val temperature: Double = 0.3,
        val max_tokens: Int = 500
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class OpenAIResponse(
        val choices: List<Choice>,
        val usage: Usage?
    )

    data class Choice(
        val message: Message,
        val finish_reason: String
    )

    data class Usage(
        @JsonProperty("total_tokens") val totalTokens: Int
    )

    override fun analyze(prompt: String): String {
        return try {
            val request = OpenAIRequest(
                messages = listOf(
                    Message(role = "system", content = "Ты опытный QA инженер, анализирующий покрытие тестами."),
                    Message(role = "user", content = prompt)
                )
            )

            callOpenAI(request).choices.firstOrNull()?.message?.content
                ?: "Не удалось получить анализ"
        } catch (e: Exception) {
            "Ошибка при анализе: ${e.message}"
        }
    }

    override fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            Проанализируй, насколько следующий тестовый код покрывает бизнес-требование.
            
            БИЗНЕС-ТРЕБОВАНИЕ:
            $requirement
            
            ТЕСТОВЫЙ КОД:
            ```
            $testCode
            ```
            
            Пожалуйста, предоставь анализ в следующем формате:
            1. **Соответствие**: Да/Нет/Частично (и объясни почему)
            2. **Покрытые аспекты**: какие части требования покрыты тестом
            3. **Непокрытые аспекты**: какие части требования не покрыты
            4. **Уверенность**: Высокая/Средняя/Низкая
            5. **Рекомендации**: что нужно добавить в тест для лучшего покрытия
            
            Будь конкретным и ссылайся на код теста.
        """.trimIndent()

        return analyze(prompt)
    }

    private fun callOpenAI(request: OpenAIRequest): OpenAIResponse {
        val json = mapper.writeValueAsString(request)

        val httpRequest = Request.Builder()
            .url("http://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw Exception("Пустой ответ от OpenAI")

        if (!response.isSuccessful) {
            throw Exception("Ошибка OpenAI: $responseBody")
        }

        return mapper.readValue(responseBody, OpenAIResponse::class.java)
    }
}