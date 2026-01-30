package com.semantic.coverage.parser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.semantic.coverage.dto.BusinessRequirement
import java.io.File

class RequirementsLoader {

    private val mapper = ObjectMapper().apply {
        registerKotlinModule()
        findAndRegisterModules()
    }

    fun loadFromFile(filePath: String): List<BusinessRequirement> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                println("⚠️  Requirements file not found: $filePath")
                return createDefaultRequirements()
            }

            println("📋 Loading requirements from: ${file.absolutePath}")
            println("   File size: ${file.length()} bytes")

            val content = file.readText()
            println("   File content preview (first 300 chars): ${content.take(300)}...")

            // Прямой парсинг JSON массива с полной поддержкой всех полей
            return try {
                val requirements = mapper.readValue<List<BusinessRequirement>>(content)
                println("✅ Successfully loaded ${requirements.size} requirements with full structure")
                requirements
            } catch (e: Exception) {
                println("⚠️  Error parsing as full structure: ${e.message}")
                // Fallback на упрощенный парсинг
                parseWithSimpleParser(content)
            }

        } catch (e: Exception) {
            println("❌ Error reading requirements file: ${e.message}")
            e.printStackTrace()
            createDefaultRequirements()
        }
    }

    private fun parseWithSimpleParser(content: String): List<BusinessRequirement> {
        val requirements = mutableListOf<BusinessRequirement>()

        // Расширенный парсинг для поддержки всех полей
        val pattern = """\{[^}]*"id"\s*:\s*"([^"]+)"[^}]*"title"\s*:\s*"([^"]+)"[^}]*"description"\s*:\s*"([^"]+)"[^}]*\}""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val matches = pattern.findAll(content)

        matches.forEach { match ->
            if (match.groupValues.size >= 4) {
                requirements.add(
                    BusinessRequirement(
                        id = match.groupValues[1],
                        title = match.groupValues[2],
                        description = match.groupValues[3]
                    )
                )
            }
        }

        if (requirements.isNotEmpty()) {
            println("✅ Simple parser loaded ${requirements.size} requirements")
            return requirements
        }

        // Еще более простой парсер как крайний вариант
        val simplePattern = """"id"\s*:\s*"([^"]+)".*?"title"\s*:\s*"([^"]+)".*?"description"\s*:\s*"([^"]+)"""".toRegex(
            RegexOption.DOT_MATCHES_ALL
        )

        val simpleMatches = simplePattern.findAll(content)
        simpleMatches.forEach { match ->
            if (match.groupValues.size >= 4) {
                requirements.add(
                    BusinessRequirement(
                        id = match.groupValues[1],
                        title = match.groupValues[2],
                        description = match.groupValues[3]
                    )
                )
            }
        }

        println("⚠️  Simple parser loaded ${requirements.size} requirements")
        return if (requirements.isEmpty()) createDefaultRequirements() else requirements
    }

    fun createDefaultRequirements(): List<BusinessRequirement> {
        println("⚠️  Creating default requirements with extended structure")

        return listOf(
            BusinessRequirement(
                id = "REQ-AUTH-01",
                title = "Система регистрации и верификации пользователей",
                description = "Пользователь может зарегистрироваться в системе, указав email и пароль, и подтвердив согласие с пользовательским соглашением. Система отправляет email подтверждения для верификации аккаунта.",
                category = "authentication",
                priority = "high",
                acceptanceCriteria = listOf(
                    "Пользователь вводит email, пароль и принимает соглашение",
                    "Система проверяет уникальность email",
                    "Отправляется email с ссылкой подтверждения"
                ),
                tags = listOf("registration", "email-verification", "onboarding")
            ),
            BusinessRequirement(
                id = "REQ-AUTH-02",
                title = "Управление сессиями и аутентификацией",
                description = "Пользователь может войти в систему с помощью email и пароля. Система создает защищенную JWT-сессию и управляет её жизненным циклом.",
                category = "authentication",
                priority = "high",
                acceptanceCriteria = listOf(
                    "Вход с корректными email/паролем создает JWT-токен",
                    "Токен используется для авторизации последующих запросов",
                    "Выход из системы инвалидирует токен"
                ),
                tags = listOf("login", "jwt", "session-management")
            ),
            BusinessRequirement(
                id = "REQ-EXERCISE-01",
                title = "Библиотека аудио-упражнений и отслеживание прогресса",
                description = "Пользователь получает доступ к структурированной библиотеке аудио-упражнений, организованных по сериям и уровням сложности. Система отслеживает прогресс выполнения.",
                category = "exercises",
                priority = "high",
                acceptanceCriteria = listOf(
                    "Отображение доступных серий упражнений",
                    "Воспроизведение аудио-контента с контролем громкости",
                    "Сохранение прогресса после каждого упражнения"
                ),
                tags = listOf("audio-playback", "progress-tracking", "exercises")
            )
        )
    }

    fun saveRequirements(requirements: List<BusinessRequirement>, filePath: String) {
        try {
            val file = File(filePath)
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, requirements)
            println("✅ Requirements saved to: $filePath")
            println("   Total requirements: ${requirements.size}")
        } catch (e: Exception) {
            println("❌ Error saving requirements: ${e.message}")
            e.printStackTrace()
        }
    }
}