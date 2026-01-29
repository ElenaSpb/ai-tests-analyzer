
package com.semantic.coverage.aiServices

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OllamaService(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "llama3.2"
) : AiService {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    data class OllamaRequest(
        val model: String,
        val prompt: String,
        val stream: Boolean = false,
        val options: Map<String, Any> = mapOf(
            "temperature" to 0.3,
            "num_predict" to 500
        )
    )

    data class OllamaResponse(
        val response: String
    )

    override fun analyze(prompt: String): String {
        return try {
            val request = OllamaRequest(model = model, prompt = prompt)
            val json = mapper.writeValueAsString(request)

            val httpRequest = Request.Builder()
                .url("$baseUrl/api/generate")
                .post(json.toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: return "Ошибка: пустой ответ"

            if (response.isSuccessful) {
                mapper.readValue(responseBody, OllamaResponse::class.java).response
            } else {
                "Ошибка Ollama: $responseBody"
            }
        } catch (e: Exception) {
            "Ошибка при анализе: ${e.message}"
        }
    }

    override fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            [INST] Ты QA инженер. Проанализируй покрытие требования тестом.
            
            ТРЕБОВАНИЕ: $requirement
            
            ТЕСТ:
            ```kotlin
            $testCode
            ```
            
            Ответь в структурированном виде:
            1. Соответствие: [Да/Нет/Частично]
            2. Покрытые аспекты требования:
            3. Непокрытые аспекты:
            4. Уверенность: [Высокая/Средняя/Низкая]
            5. Рекомендации по улучшению теста:
            
            Будь максимально конкретным. [/INST]
        """.trimIndent()

        return analyze(prompt)
    }
}