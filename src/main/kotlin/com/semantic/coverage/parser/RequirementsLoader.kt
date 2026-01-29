package com.semantic.coverage.parser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.semantic.coverage.dto.Requirement
import java.io.File

class RequirementsLoader {

    private val mapper = ObjectMapper().apply {
        findAndRegisterModules()
    }

    fun loadFromFile(filePath: String): List<Requirement> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                println("⚠️  Requirements file not found: $filePath")
                return createDefaultRequirements()
            }

            println("📋 Loading requirements from: ${file.absolutePath}")
            println("   File size: ${file.length()} bytes")

            val content = file.readText()
            println("   File content (first 200 chars): ${content.take(200)}...")

            // Пробуем разные форматы

            // 1. Пробуем как массив требований
            return try {
                val requirements = mapper.readValue<Array<Requirement>>(content).toList()
                println("✅ Successfully loaded ${requirements.size} requirements as array")
                requirements
            } catch (e: Exception) {
                // 2. Пробуем как объект с полем requirements
                try {
                    val wrapper = mapper.readTree(content)
                    if (wrapper.has("requirements")) {
                        val requirements = mapper.readValue<List<Requirement>>(
                            wrapper.get("requirements").toString()
                        )
                        println("✅ Successfully loaded ${requirements.size} requirements from wrapper")
                        requirements
                    } else {
                        // 3. Fallback на простой парсер
                        println("⚠️  JSON doesn't contain 'requirements' field")
                        parseWithSimpleParser(content)
                    }
                } catch (e2: Exception) {
                    // 4. Fallback на простой парсер
                    println("⚠️  Error parsing as wrapper: ${e2.message}")
                    parseWithSimpleParser(content)
                }
            }

        } catch (e: Exception) {
            println("❌ Error reading requirements file: ${e.message}")
            createDefaultRequirements()
        }
    }

    private fun parseWithSimpleParser(content: String): List<Requirement> {
        val requirements = mutableListOf<Requirement>()

        // Улучшенный regex для парсинга JSON объектов
        val pattern = """
            \{\s*
            ["']id["']\s*:\s*["']([^"']+)["']\s*,\s*
            ["']title["']\s*:\s*["']([^"']+)["']\s*,\s*
            ["']description["']\s*:\s*["']([^"']+)["']
        """.trimIndent().toRegex(RegexOption.DOT_MATCHES_ALL)

        val matches = pattern.findAll(content)

        matches.forEach { match ->
            if (match.groupValues.size >= 4) {
                requirements.add(
                    Requirement(
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

        // Еще более простой парсер
        val simplePattern = """"id":\s*"([^"]+)".*?"title":\s*"([^"]+)".*?"description":\s*"([^"]+)"""".toRegex(
            RegexOption.DOT_MATCHES_ALL
        )

        val simpleMatches = simplePattern.findAll(content)
        simpleMatches.forEach { match ->
            if (match.groupValues.size >= 4) {
                requirements.add(
                    Requirement(
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

    fun createDefaultRequirements(): List<Requirement> {
        println("⚠️  Creating default requirements")

        return listOf(
            Requirement(
                id = "REQ-AUTH-01",
                title = "User Registration",
                description = "Пользователь может зарегистрироваться в системе, указав email и пароль"
            ),
            Requirement(
                id = "REQ-AUTH-02",
                title = "User Login",
                description = "Пользователь может войти в систему, используя email и пароль"
            ),
            Requirement(
                id = "REQ-AUTH-03",
                title = "Password Recovery",
                description = "Пользователь может восстановить пароль, запросив сброс на email"
            ),
            Requirement(
                id = "REQ-PROF-01",
                title = "Profile Management",
                description = "Пользователь может просмотреть и отредактировать данные своего профиля (имя, аватар)"
            ),
            Requirement(
                id = "REQ-PROF-02",
                title = "Training Statistics",
                description = "Система отображает статистику тренировок пользователя (прогресс, активность) в личном кабинете"
            )
        )
    }

    fun saveRequirements(requirements: List<Requirement>, filePath: String) {
        try {
            val file = File(filePath)
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, requirements)
            println("✅ Requirements saved to: $filePath")
        } catch (e: Exception) {
            println("❌ Error saving requirements: ${e.message}")
        }
    }
}