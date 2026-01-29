// GeminiService.kt
package com.semantic.coverage.aiServices

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiService(private val apiKey: String) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            Analyze test coverage for this requirement:
            
            REQUIREMENT: $requirement
            
            TEST CODE:
            ```kotlin
            $testCode
            ```
            
            Provide analysis in Russian with:
            1. Coverage match: Yes/No/Partial
            2. Covered aspects
            3. Missing aspects  
            4. Confidence: High/Medium/Low
            5. Recommendations
        """.trimIndent()

        val requestBody = """
        {
            "contents": [{
                "parts": [{
                    "text": "$prompt"
                }]
            }]
        }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=$apiKey")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.body?.string() ?: "No response"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}