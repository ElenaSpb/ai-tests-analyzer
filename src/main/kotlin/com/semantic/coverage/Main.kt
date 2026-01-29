package com.semantic.coverage

import analyze.CoverageOpenAiAnalyzer
import com.semantic.coverage.aiServices.AiService
import com.semantic.coverage.aiServices.OllamaService
import com.semantic.coverage.aiServices.OpenAiV2Service
import com.semantic.coverage.dto.Requirement
import com.semantic.coverage.embedding.LocalEmbeddingService
import com.semantic.coverage.embedding.OpenAIEmbeddingService
import com.semantic.coverage.embedding.SentenceTransformerPythonService
import com.semantic.coverage.parser.TestsParser
import com.semantic.coverage.report.ReportWithAiGenerator
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import java.io.File

fun main(args: Array<String>) {
    val parser = ArgParser("semantic-coverage")
    val projectPath by parser.option(
        ArgType.String,
        shortName = "p",
        description = "Path to project root"
    ).default("C:\\brn\\555\\src\\test\\kotlin\\com\\epam\\brn")

    val requirementsPath by parser.option(
        ArgType.String,
        shortName = "r",
        description = "Path to requirements file (JSON)"
    ).default("requirements.json")

    val outputPath by parser.option(
        ArgType.String,
        shortName = "o",
        description = "Output path for report"
    ).default("./coverage-report.html")

    val maxFiles by parser.option(
        ArgType.Int,
        shortName = "m",
        description = "Maximum number of test files to process"
    ).default(118)

    val useAI by parser.option(
        ArgType.Boolean,
        shortName = "ai",
        description = "Use AI analysis (requires API key)"
    ).default(true)

    val openaiKey by parser.option(
        ArgType.String,
        shortName = "openai-key",
        description = "OpenAI API key"
    ).default("")

    val ollamaUrl by parser.option(
        ArgType.String,
        shortName = "ollama",
        description = "Ollama URL (e.g., http://localhost:11434)"
    ).default("")

    parser.parse(args)

    println("Starting Semantic Coverage Analysis...")
    println("Project: $projectPath")
    println("Max files: $maxFiles")

    // Инициализация AI сервиса
    val aiService: AiService? = when {
        useAI && openaiKey.isNotBlank() -> {
            println("🤖 Using OpenAI for analysis")
            OpenAiV2Service(openaiKey)
        }
        useAI && ollamaUrl.isNotBlank() -> {
            println("🤖 Using Ollama for analysis")
            OllamaService(ollamaUrl)
        }
        useAI -> {
            println("⚠️  AI requested but no API key provided. Using simple analysis.")
            null
        }
        else -> null
    }

    // Создание анализатора с AI
    val embeddingService = SentenceTransformerPythonService()
    val localEmbeddingService = LocalEmbeddingService()
    val openAIEmbeddingService = OpenAIEmbeddingService(openaiKey)
    val analyzer = CoverageOpenAiAnalyzer(
        embeddingService = openAIEmbeddingService,
        aiService = aiService,
        useAI = useAI && aiService != null
    )

    try {
        // 1. Инициализация компонентов
        val testParser = TestsParser()
        val reporter = ReportWithAiGenerator()

        // 2. Загрузка требований
        val requirements = if (File(requirementsPath).exists()) {
            loadRequirementsFromFile(requirementsPath)
        } else {
            // Пример требований для brainup.site
            createDefaultRequirements()
        }

        println("📋 Loaded ${requirements.size} requirements")

        // 3. Парсинг тестов
        println("Parsing test files...")
        val testChunks = testParser.parseTestFiles(projectPath, maxFiles)
        println("   Found ${testChunks.size} test chunks")

        // 4. Анализ покрытия
        println("Analyzing semantic coverage...")
        val coverageReports = analyzer.analyzeCoverage(requirements, testChunks)

        // 5. Генерация отчетов
        println("📊 Generating reports...")
        reporter.generateConsoleReport(coverageReports)
        reporter.generateHtmlReport(coverageReports, outputPath)

        println("Analysis complete!")
        println("HTML report saved to: $outputPath")

    } catch (e: Exception) {
        println("Error during analysis: ${e.message}")
        e.printStackTrace()
    }
}

fun loadRequirementsFromFile(filePath: String): List<Requirement> {
    val file = File(filePath)

    if (!file.exists()) {
        println("Requirements file not found: $filePath")
        return createDefaultRequirements()
    }

    println("📋 Loading requirements from: ${file.absolutePath}")
    println("   File size: ${file.length()} bytes")

    try {
        val content = file.readText()
        println("   File content (first 200 chars): ${content.take(200)}...")

        // Используем Jackson для парсинга JSON
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        // Читаем как массив объектов
        val requirements = mapper.readValue(content, Array<Requirement>::class.java).toList()

        println("✅ Successfully loaded ${requirements.size} requirements")
        requirements.forEachIndexed { i, req ->
            println("   ${i + 1}. ${req.id}: ${req.title}")
        }

        return requirements

    } catch (e: com.fasterxml.jackson.core.JsonParseException) {
        println("JSON parsing error: ${e.message}")
        println("   Trying fallback parser...")
        return parseWithSimpleParser(file)

    } catch (e: Exception) {
        println("Error reading requirements file: ${e.message}")
        e.printStackTrace()
        return createDefaultRequirements()
    }
}

// Fallback парсер на случай проблем с Jackson
private fun parseWithSimpleParser(file: File): List<Requirement> {
    val content = file.readText()
    val requirements = mutableListOf<Requirement>()

    // Улучшенный regex
    val pattern = """
        \{\s*
        "id"\s*:\s*"([^"]+)"\s*,\s*
        "title"\s*:\s*"([^"]+)"\s*,\s*
        "description"\s*:\s*"([^"]+)"[^}]*\}
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