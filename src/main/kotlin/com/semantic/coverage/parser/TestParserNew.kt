package com.semantic.coverage.parser

import com.semantic.coverage.dto.TestChunk
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.walk
import kotlin.math.absoluteValue

@OptIn(ExperimentalPathApi::class)
class TestParserNew {
    private val testPatterns = listOf(
        "*Test.kt",
        "*Spec.kt",
        "*test*.kt",
        "*test*.java",
        "*Test.java",
        "*.test.js",
        "*.spec.js",
        "*test*.py",
        "*_test.py"
    )

    fun parseTestFiles(projectRoot: String, maxFiles: Int = 50): List<TestChunk> {
        val testChunks = mutableListOf<TestChunk>()
        val projectPath = Path(projectRoot)

        // Собираем ВСЕ файлы, затем фильтруем
        val allFiles = mutableListOf<File>()

        projectPath.walk()
            .filter { it.toFile().isFile }
            .take(maxFiles * 10) // Берем больше файлов для фильтрации
            .forEach { allFiles.add(it.toFile()) }

        // Фильтруем по шаблонам
        val testFiles = allFiles.filter { file ->
            testPatterns.any { pattern ->
                matchesPattern(file.name, pattern)
            }
        }.take(maxFiles)

        println("Found ${testFiles.size} test files (from ${allFiles.size} total)")

        testFiles.forEach { file ->
            try {
                val chunks = extractChunksFromFile(file)
                testChunks.addAll(chunks)
                println("  ✓ ${file.name} → ${chunks.size} chunks")
            } catch (e: Exception) {
                println("  ✗ ${file.name}: ${e.message}")
            }
        }

        return testChunks.take(maxFiles * 5)
    }

    private fun matchesPattern(fileName: String, pattern: String): Boolean {
        return try {
            // Преобразуем glob в regex
            val regexPattern = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            fileName.matches(regexPattern.toRegex(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            // Fallback: простое сравнение
            pattern.contains("*").let {
                if (it) {
                    val cleanPattern = pattern.replace("*", "")
                    fileName.contains(cleanPattern, ignoreCase = true)
                } else {
                    fileName.equals(pattern, ignoreCase = true)
                }
            }
        }
    }

    private fun extractChunksFromFile(file: File): List<TestChunk> {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

        if (content.isEmpty()) return emptyList()

        val chunks = mutableListOf<TestChunk>()

        when (file.extension.lowercase()) {
            "kt", "java" -> chunks.addAll(parseKotlinJavaTests(file, content))
            "js", "jsx", "ts", "tsx" -> chunks.addAll(parseJavascriptTests(file, content))
            "py" -> chunks.addAll(parsePythonTests(file, content))
            else -> {
                // Общий парсер для других языков
                val chunk = TestChunk(
                    id = "${file.nameWithoutExtension}_general_${System.currentTimeMillis()}",
                    filePath = file.path,
                    testName = file.nameWithoutExtension,
                    content = content.take(1000),
                    metadata = mapOf("language" to file.extension)
                )
                chunks.add(chunk)
            }
        }

        return chunks
    }

    private fun parseKotlinJavaTests(file: File, content: String): List<TestChunk> {
        val chunks = mutableListOf<TestChunk>()
        val lines = content.lines()
        var currentClass = ""
        var inTest = false
        var testContent = StringBuilder()
        var testName = ""
        var lineNumber = 0

        for ((index, line) in lines.withIndex()) {
            lineNumber = index + 1
            val trimmedLine = line.trim()

            // Поиск объявления класса
            if (trimmedLine.startsWith("class ") || trimmedLine.startsWith("@Test class ")) {
                currentClass = extractClassName(trimmedLine)
                inTest = false
            }

            // Поиск тестовых методов
            val isTestMethod = isTestMethodLine(trimmedLine)

            if (isTestMethod) {
                // Сохраняем предыдущий тест
                if (inTest && testName.isNotEmpty()) {
                    chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), lineNumber))
                }

                // Начинаем новый тест
                testName = extractTestName(trimmedLine)
                testContent = StringBuilder(trimmedLine + "\n")
                inTest = true
            } else if (inTest) {
                testContent.appendLine(line)
            }
        }

        // Добавляем последний тест
        if (inTest && testName.isNotEmpty()) {
            chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), lineNumber))
        }

        return chunks
    }

    private fun extractClassName(line: String): String {
        return line.substringAfter("class ")
            .substringBefore("(")
            .substringBefore("{")
            .substringBefore(":")
            .substringBefore("<")
            .substringBefore(" ")
            .trim()
    }

    private fun isTestMethodLine(line: String): Boolean {
        return line.contains("@Test") ||
                line.matches(".*fun\\s+test.*".toRegex(RegexOption.IGNORE_CASE)) ||
                line.matches(".*void\\s+test.*".toRegex(RegexOption.IGNORE_CASE)) ||
                line.matches(".*def\\s+test.*".toRegex(RegexOption.IGNORE_CASE))
    }

    private fun parseJavascriptTests(file: File, content: String): List<TestChunk> {
        val chunks = mutableListOf<TestChunk>()
        val regex = """(describe|it|test)\s*\(['"`](.*?)['"`]""".toRegex(RegexOption.IGNORE_CASE)
        val matches = regex.findAll(content)

        matches.forEach { match ->
            val testType = match.groupValues[1]
            val testName = match.groupValues[2]
            val startIndex = match.range.first
            val endIndex = findClosingBracket(content, startIndex)

            if (endIndex > startIndex) {
                val testBody = content.substring(startIndex, endIndex + 1)
                val chunk = TestChunk(
                    id = "${file.nameWithoutExtension}_${testName.hashCode().absoluteValue}",
                    filePath = file.path,
                    testName = "$testType: $testName",
                    content = testBody,
                    metadata = mapOf(
                        "type" to testType,
                        "language" to file.extension
                    )
                )
                chunks.add(chunk)
            }
        }

        return chunks
    }

    private fun parsePythonTests(file: File, content: String): List<TestChunk> {
        val chunks = mutableListOf<TestChunk>()
        val lines = content.lines()
        var currentClass = ""
        var inTest = false
        var testContent = StringBuilder()
        var testName = ""
        var indentLevel = 0

        for (line in lines) {
            val trimmedLine = line.trim()

            if (trimmedLine.startsWith("class ")) {
                currentClass = trimmedLine.substringAfter("class ").substringBefore("(").trim()
            }

            if (trimmedLine.startsWith("def test_")) {
                if (inTest && testName.isNotEmpty()) {
                    chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), 0))
                }

                testName = trimmedLine.substringAfter("def ").substringBefore("(").trim()
                testContent = StringBuilder(trimmedLine + "\n")
                inTest = true
                indentLevel = line.takeWhile { it == ' ' }.length
            } else if (inTest) {
                val currentIndent = line.takeWhile { it == ' ' }.length
                if (currentIndent <= indentLevel && trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                    // Конец теста
                    chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), 0))
                    inTest = false
                } else {
                    testContent.appendLine(line)
                }
            }
        }

        if (inTest && testName.isNotEmpty()) {
            chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), 0))
        }

        return chunks
    }

    private fun createTestChunk(file: File, className: String, testName: String, content: String, lineNumber: Int): TestChunk {
        val fullTestName = if (className.isNotEmpty()) {
            "$className.$testName"
        } else {
            testName
        }

        return TestChunk(
            id = "${file.name}_${fullTestName}_${System.currentTimeMillis()}".hashCode().absoluteValue.toString(),
            filePath = file.path,
            testName = fullTestName,
            content = content.take(2000),
            metadata = mapOf(
                "className" to className,
                "line" to lineNumber.toString(),
                "fileSize" to file.length().toString()
            )
        )
    }

    private fun extractTestName(line: String): String {
        return when {
            line.contains("fun ") -> line.substringAfter("fun ").substringBefore("(").trim()
            line.contains("void ") -> line.substringAfter("void ").substringBefore("(").trim()
            line.contains("def ") -> line.substringAfter("def ").substringBefore("(").trim()
            line.contains("@Test") -> {
                // Ищем имя метода после @Test
                val nextPart = line.substringAfter("@Test")
                when {
                    nextPart.contains("fun ") -> nextPart.substringAfter("fun ").substringBefore("(").trim()
                    nextPart.contains("void ") -> nextPart.substringAfter("void ").substringBefore("(").trim()
                    else -> "test_method_${line.hashCode().absoluteValue}"
                }
            }
            else -> "unnamed_test_${line.hashCode().absoluteValue}"
        }
    }

    private fun findClosingBracket(content: String, startIndex: Int): Int {
        var bracketCount = 0
        var braceCount = 0
        var inString = false
        var stringChar: Char? = null
        var escaped = false

        for (i in startIndex until content.length) {
            val char = content[i]

            // Обработка экранирования
            if (escaped) {
                escaped = false
                continue
            }

            if (char == '\\') {
                escaped = true
                continue
            }

            // Обработка строковых литералов
            if (char == '"' || char == '\'' || char == '`') {
                if (!inString) {
                    inString = true
                    stringChar = char
                } else if (stringChar == char && !escaped) {
                    inString = false
                    stringChar = null
                }
            }

            if (!inString) {
                when (char) {
                    '(' -> bracketCount++
                    ')' -> {
                        bracketCount--
                        if (bracketCount == 0 && braceCount == 0) {
                            return i
                        }
                    }
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (bracketCount == 0 && braceCount == 0) {
                            return i
                        }
                    }
                }
            }
        }
        return -1
    }
}