package com.semantic.coverage.aiServices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiSslService(
    private val apiKey: String
) : AiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Serializable
    data class OpenAIRequest(
        val model: String = "gpt-3.5-turbo-0125",
        val messages: List<Message>,
        val temperature: Double = 0.1,
        @SerialName("max_tokens")
        val maxTokens: Int = 1000
    )

    @Serializable
    data class Message(
        val role: String,
        val content: String
    )

    @Serializable
    data class OpenAIResponse(
        val id: String? = null,
        val choices: List<Choice> = emptyList(),
        val error: OpenAIError? = null
    ) {
        @Serializable
        data class Choice(
            val index: Int = 0,
            val message: Message? = null,
            @SerialName("finish_reason")
            val finishReason: String? = null
        )

        @Serializable
        data class OpenAIError(
            val message: String? = null,
            val type: String? = null,
            val code: String? = null
        )
    }

    override fun analyze(prompt: String): String {
        return try {
            val request = OpenAIRequest(
                messages = listOf(
                    Message(role = "system", content = "You are a senior QA engineer analyzing test coverage."),
                    Message(role = "user", content = prompt)
                )
            )

            val response = callOpenAI(request)
            response.choices.firstOrNull()?.message?.content ?: "No response content"

        } catch (e: Exception) {
            println("❌ OpenAI analysis error: ${e.message}")
            "Error analyzing with OpenAI: ${e.message}"
        }
    }

    override fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            Analyze how well this test code covers the business requirement.
            
            BUSINESS REQUIREMENT:
            $requirement
            
            TEST CODE:
            ```kotlin
            $testCode
            ```
            
            Provide analysis in Russian with:
            1. Coverage match: Full/Partial/None (explain why)
            2. Covered aspects of the requirement
            3. Missing aspects of the requirement  
            4. Confidence level: High/Medium/Low
            5. Recommendations for improving test coverage
            
            Be specific and reference the test code.
        """.trimIndent()

        return analyze(prompt)
    }

    private fun callOpenAI(request: OpenAIRequest): OpenAIResponse {
        val jsonBody = json.encodeToString(request)

        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        println("📡 Sending request to OpenAI API...")

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response from OpenAI")

        if (!response.isSuccessful) {
            println("❌ OpenAI error response: $responseBody")
            throw Exception("OpenAI API error: ${response.code}")
        }

        return json.decodeFromString(responseBody)
    }

    fun testApiKey(): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}