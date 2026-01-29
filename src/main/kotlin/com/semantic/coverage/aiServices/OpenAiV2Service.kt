package com.semantic.coverage.aiServices

import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

@OptIn(BetaOpenAI::class)
class OpenAiV2Service(
    private val apiKey: String
) : AiService {

    private val openAI: OpenAI by lazy {
        println("🔧 Инициализация OpenAI клиента...")
        OpenAI(
            OpenAIConfig(
                token = apiKey,
                timeout = Timeout(socket = 60.seconds),
            )
        )
    }

    override fun analyze(prompt: String): String {
        return try {
            println("🤖 Запрос к OpenAI...")

            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId("gpt-4-0125-preview"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = "Ты - опытный QA инженер, специализирующийся на анализе покрытия тестами. Отвечай только на русском языке."
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                ),
                temperature = 0.1,
                maxTokens = 1000
            )

            // Используем runBlocking для вызова suspend функции
            val response = runBlocking {
                try {
                    val chatCompletion = openAI.chatCompletion(chatCompletionRequest)
                    chatCompletion.choices.firstOrNull()?.message?.content
                        ?: "Нет ответа от модели"
                } catch (e: Exception) {
                    "Ошибка при выполнении запроса: ${e.message}"
                }
            }

            println("✅ Ответ получен")
            response

        } catch (e: Exception) {
            println("❌ Ошибка OpenAI: ${e.message}")
            "Ошибка при анализе с OpenAI: ${e.message}"
        }
    }

    override fun analyzeTestCoverage(requirement: String, testCode: String): String {
        val prompt = """
            ПРОАНАЛИЗИРУЙ, насколько хорошо этот тестовый код покрывает бизнес-требование.
            
            БИЗНЕС-ТРЕБОВАНИЕ:
            $requirement
            
            ТЕСТОВЫЙ КОД:
            ```kotlin
            $testCode
            ```
            
            Предоставь анализ на русском языке:
            1. ✅ Соответствие покрытия: Полное/Частичное/Отсутствует (объясни почему)
            2. 📋 Покрытые аспекты требования
            3. ⚠️ Непокрытые аспекты требования  
            4. 🎯 Уровень уверенности: Высокий/Средний/Низкий
            5. 💡 Рекомендации по улучшению покрытия тестами
            
            Будь конкретным и ссылайся на тестовый код.
            Отвечай в формате Markdown.
        """.trimIndent()

        return analyze(prompt)
    }

    fun testApiKey(): Boolean {
        println("🔑 Проверка API ключа OpenAI...")

        return try {
            runBlocking {
                openAI.models()
            }
            println("✅ API ключ рабочий")
            true
        } catch (e: Exception) {
            println("❌ API ключ не работает: ${e.message}")
            false
        }
    }
}