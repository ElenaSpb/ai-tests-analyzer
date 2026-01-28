package com.semantic.coverage

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
    )

    val outputPath by parser.option(
        ArgType.String,
        shortName = "o",
        description = "Output path for report"
    ).default("./coverage-report.html")

    val maxFiles by parser.option(
        ArgType.Int,
        shortName = "m",
        description = "Maximum number of test files to process"
    ).default(50)

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
    ).default("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")

    parser.parse(args)

    println("Starting Semantic Coverage Analysis...")
    println("Project: $projectPath")
    println("Max files: $maxFiles")

    // Инициализация AI сервиса
    val aiService = when {
        useAI && openaiKey.isNotBlank() -> {
            println("🤖 Using OpenAI for analysis")
            OpenAIService(openaiKey)
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
    val embeddingService = LocalEmbeddingService()
//    val analyzer = CoverageOpenAiAnalyzer(
//        embeddingService = embeddingService,
//        openAIService = aiService,
//        useAI = useAI && aiService != null
//    )

    try {
        // 1. Инициализация компонентов
//        val testParser = TestParser()
        val testParser = TestParserNew()
        val embeddingService = LocalEmbeddingService()
        val analyzer = CoverageAnalyzer(embeddingService)
        val reporter = ReportGenerator()

        // 2. Загрузка требований
        val requirements = if (requirementsPath != null && File(requirementsPath).exists()) {
            loadRequirementsFromFile(requirementsPath!!)
        } else {
            // Пример требований для brainup.site
            createSampleRequirements()
        }

        println("📋 Loaded ${requirements.size} requirements")

        // 3. Парсинг тестов
        println("🔍 Parsing test files...")
        val testChunks = testParser.parseTestFiles(projectPath, maxFiles)
        println("   Found ${testChunks.size} test chunks")

        // 4. Анализ покрытия
        println("🧠 Analyzing semantic coverage...")
        val coverageReports = analyzer.analyzeCoverage(requirements, testChunks)

        // 5. Генерация отчетов
        println("📊 Generating reports...")
        reporter.generateConsoleReport(coverageReports)
        reporter.generateHtmlReport(coverageReports, outputPath)

        println("✅ Analysis complete!")
        println("📄 HTML report saved to: $outputPath")

    } catch (e: Exception) {
        println("❌ Error during analysis: ${e.message}")
        e.printStackTrace()
    }
}

fun loadRequirementsFromFile(filePath: String): List<Requirement> {
    val file = File(filePath)
    val content = file.readText()

    // Простой парсер JSON для требований
    return try {
        val regex = """\{"id":\s*"([^"]+)",\s*"title":\s*"([^"]+)",\s*"description":\s*"([^"]+)""".toRegex()
        regex.findAll(content).map { match ->
            Requirement(
                id = match.groupValues[1],
                title = match.groupValues[2],
                description = match.groupValues[3]
            )
        }.toList()
    } catch (e: Exception) {
        println("Warning: Could not parse requirements file, using sample requirements")
        createSampleRequirements()
    }
}

fun createSampleRequirements(): List<Requirement> {
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