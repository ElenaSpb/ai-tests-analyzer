package com.semantic.coverage

import com.semantic.coverage.analyze.CoverageAiAnalyzer
import com.semantic.coverage.aiServices.AiService
import com.semantic.coverage.aiServices.MistralService
import com.semantic.coverage.aiServices.OllamaService
import com.semantic.coverage.aiServices.OpenAiV2Service
import com.semantic.coverage.embedding.LocalEmbeddingService
import com.semantic.coverage.embedding.OpenAIEmbeddingService
import com.semantic.coverage.parser.RequirementsLoader
import com.semantic.coverage.parser.TestsParser
import com.semantic.coverage.report.ReportWithAiAnalyzeGenerator
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


    val mistralKey by parser.option(
        ArgType.String,
        shortName = "mistral-key",
        description = "Mistral api-key"
    ).default("dPw0OW84uif7ozCOi6CDIojujopazKva")

    parser.parse(args)

    println("🚀 Starting Semantic Coverage Analysis...")
    println("📁 Project: $projectPath")
    println("📄 Max files: $maxFiles")
    println("🤖 AI enabled: $useAI")

    // Инициализация AI сервиса
    val aiService: AiService? = initializeAiService(useAI, openaiKey, ollamaUrl, mistralKey)

    try {
        // 1. Инициализация компонентов
        val requirementsLoader = RequirementsLoader()
        val testParser = TestsParser()
        val reporter = ReportWithAiAnalyzeGenerator()

        // 2. Загрузка требований
        val requirements = requirementsLoader.loadFromFile(requirementsPath)
        println("📋 Loaded ${requirements.size} requirements")
        requirements.forEachIndexed { i, req ->
            println("   ${i + 1}. ${req.id}: ${req.title}")
        }

        // 3. Инициализация анализатора
        val localEmbeddingService = LocalEmbeddingService()
        val openAiEmbeddingService = OpenAIEmbeddingService(openaiKey)
        val analyzer = CoverageAiAnalyzer(
            embeddingService = localEmbeddingService,
            aiService = aiService,
            useAI = useAI && aiService != null
        )

        // 4. Парсинг тестов
        println("\n🔍 Parsing test files...")
        val testChunks = testParser.parseTestFiles(projectPath, maxFiles)
        println("   ✅ Found ${testChunks.size} test chunks")

        // 5. Анализ покрытия
        println("\n🧠 Analyzing semantic coverage...")
        val coverageReports = analyzer.analyzeCoverage(requirements, testChunks)

        // 6. Генерация отчетов
        println("\n📊 Generating reports...")
        reporter.generateConsoleReport(coverageReports)
        reporter.generateHtmlReport(coverageReports, outputPath)

        // Опционально: генерация диаграммы
        reporter.generateCoverageChart(coverageReports)

        println("\n✅ Analysis complete!")
        println("📄 HTML report saved to: ${File(outputPath).absolutePath}")

    } catch (e: Exception) {
        println("\n❌ Error during analysis: ${e.message}")
        e.printStackTrace()
    }
}

private fun initializeAiService(
    useAI: Boolean,
    openaiKey: String,
    ollamaUrl: String,
    mistralKey: String,
): AiService? {
    return when {
        useAI && openaiKey.isNotBlank() -> {
            println("🤖 Using OpenAI for analysis")
            try {
                val service = OpenAiV2Service(openaiKey)
                if (service.testApiKey()) {
                    println("   ✅ OpenAI API key is valid")
                } else {
                    println("   ⚠️  OpenAI API key may be invalid")
                }
                service
            } catch (e: Exception) {
                println("   ❌ Failed to initialize OpenAI: ${e.message}")
                null
            }
        }
        useAI && mistralKey.isNotBlank() -> {
            println("🤖 Using Mistral AI for analysis")
            try {
                val service = MistralService(mistralKey)
                if (service.testApiKey()) {
                    println("   ✅ Mistral API key is valid")
                } else {
                    println("   ⚠️  Mistral API key may be invalid")
                }
                service
            } catch (e: Exception) {
                println("   ❌ Failed to initialize Mistral: ${e.message}")
                null
            }
        }
        useAI && ollamaUrl.isNotBlank() -> {
            println("🤖 Using Ollama for analysis")
            try {
                val service = OllamaService(ollamaUrl)
                println("   ✅ Ollama service initialized")
                service
            } catch (e: Exception) {
                println("   ❌ Failed to initialize Ollama: ${e.message}")
                null
            }
        }
        useAI -> {
            println("⚠️  AI requested but no API key or Ollama URL provided")
            println("   Using simple analysis without AI")
            null
        }
        else -> {
            println("ℹ️  AI analysis disabled")
            null
        }
    }
}