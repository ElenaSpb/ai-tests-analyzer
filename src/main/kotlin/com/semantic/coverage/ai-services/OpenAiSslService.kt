package com.semantic.coverage.`ai-services`

import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class OpenAiSslService(
    private val apiKey: String,
    private val unsafeSSL: Boolean = false
) : AiService {

    private val client = if (unsafeSSL) {
        createUnsafeClient()
    } else {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

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

    private fun createUnsafeClient(): OkHttpClient {
        println("⚠️  WARNING: Using unsafe SSL configuration for testing!")

        try {
            // Создаем trust manager, который доверяет всем сертификатам
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            })

            // Создаем SSL context с нашим trust manager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // Создаем socket factory
            val sslSocketFactory = sslContext.socketFactory

            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true } // Принимаем любой hostname
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

        } catch (e: Exception) {
            println("⚠️  Failed to create unsafe client: ${e.message}")
            // Fallback на обычный клиент
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
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
            "Error analyzing with OpenAI: ${e.message}"
        }
    }

    override fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            Analyze how well this test code covers the business requirement.
            
            BUSINESS REQUIREMENT:
            $requirement
            
            TEST CODE:
            ```
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
        val json = mapper.writeValueAsString(request)

        // ВАЖНО: используем HTTPS!
        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMediaType))
            .build()

        println("📡 Sending request to OpenAI API...")

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response from OpenAI")

        println("📡 Response code: ${response.code}")

        if (!response.isSuccessful) {
            println("❌ OpenAI error response: $responseBody")
            throw Exception("OpenAI API error: ${response.code} - ${response.message}")
        }

        return mapper.readValue(responseBody, OpenAIResponse::class.java)
    }
}