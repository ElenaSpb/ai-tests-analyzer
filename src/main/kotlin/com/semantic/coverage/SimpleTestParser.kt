package com.semantic.coverage

// TestParser.kt (альтернативная версия без ExperimentalPathApi)

import java.io.File
import kotlin.math.absoluteValue

class SimpleTestParser {
    private val testPatterns = listOf(
        "*test*.kt",
        "*Test.kt",
        "*Spec.kt",
        "*test*.java",
        "*Test.java",
        "*.test.js",
        "*.spec.js",
        "*test*.py",
        "*_test.py"
    )

    fun parseTestFiles(projectRoot: String, maxFiles: Int = 50): List<TestChunk> {
        val testChunks = mutableListOf<TestChunk>()
        val projectDir = File(projectRoot)

        if (!projectDir.exists() || !projectDir.isDirectory) {
            throw IllegalArgumentException("Project directory does not exist: $projectRoot")
        }

        // Рекурсивно ищем файлы
        val foundFiles = mutableListOf<File>()
        findTestFiles(projectDir, foundFiles, maxFiles)

        println("Found ${foundFiles.size} test files")

        foundFiles.forEach { file ->
            try {
                val chunks = extractChunksFromFile(file)
                testChunks.addAll(chunks)
                println("  Parsed ${chunks.size} test chunks from ${file.relativeTo(projectDir)}")
            } catch (e: Exception) {
                println("  Warning: Could not parse file ${file.path}: ${e.message}")
            }
        }

        return testChunks.take(maxFiles * 5)
    }

    private fun findTestFiles(directory: File, foundFiles: MutableList<File>, maxFiles: Int) {
        if (foundFiles.size >= maxFiles) return

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (foundFiles.size >= maxFiles) break

            if (file.isDirectory) {
                // Пропускаем некоторые директории
                if (!shouldSkipDirectory(file)) {
                    findTestFiles(file, foundFiles, maxFiles)
                }
            } else if (file.isFile && isTestFile(file)) {
                foundFiles.add(file)
            }
        }
    }

    private fun shouldSkipDirectory(dir: File): Boolean {
        val skipDirs = listOf(
            ".git", ".idea", "node_modules", "build", "target",
            "dist", "out", ".gradle", ".vscode", ".next", ".nuxt",
            "__pycache__", ".pytest_cache", "coverage", ".nyc_output",
            "tmp", "temp", "logs", ".cache", "venv", "env", ".env",
            "vendor", "bin", "obj", "packages", ".vs", ".settings"
        )
        return skipDirs.any { dir.name.contains(it) }
    }

    private fun isTestFile(file: File): Boolean {
        val fileName = file.name.lowercase()
        val fileExt = file.extension.lowercase()

        // Проверяем по расширениям и именам
        return when (fileExt) {
            "kt", "java" -> fileName.contains("test") || fileName.contains("spec")
            "js", "jsx", "ts", "tsx" -> fileName.endsWith(".test.js") ||
                    fileName.endsWith(".spec.js") ||
                    fileName.endsWith(".test.jsx") ||
                    fileName.endsWith(".spec.jsx") ||
                    fileName.endsWith(".test.ts") ||
                    fileName.endsWith(".spec.ts") ||
                    fileName.endsWith(".test.tsx") ||
                    fileName.endsWith(".spec.tsx") ||
                    fileName.contains("test") && fileName.endsWith(".js") ||
                    fileName.contains("test") && fileName.endsWith(".ts")
            "py" -> fileName.endsWith("_test.py") ||
                    fileName.startsWith("test_") ||
                    fileName.contains("test") && fileName.endsWith(".py")
            else -> false
        }
    }

    private fun extractChunksFromFile(file: File): List<TestChunk> {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }

        val chunks = mutableListOf<TestChunk>()
        val fileExt = file.extension.lowercase()

        when {
            fileExt in listOf("kt", "java") -> chunks.addAll(parseKotlinJavaTests(file, content))
            fileExt in listOf("js", "jsx", "ts", "tsx") -> chunks.addAll(parseJavascriptTests(file, content))
            fileExt == "py" -> chunks.addAll(parsePythonTests(file, content))
            else -> {
                // Общий парсер для других языков
                val chunk = TestChunk(
                    id = "${file.nameWithoutExtension}_general_${System.currentTimeMillis()}",
                    filePath = file.path,
                    testName = file.nameWithoutExtension,
                    content = content.take(1000),
                    metadata = mapOf(
                        "language" to fileExt,
                        "fileSize" to file.length().toString()
                    )
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
        var testStartLine = 0

        for ((index, line) in lines.withIndex()) {
            lineNumber = index + 1
            val trimmedLine = line.trim()

            // Поиск объявления класса
            if (trimmedLine.startsWith("class ") || trimmedLine.startsWith("@Test class ") ||
                trimmedLine.startsWith("public class ") || trimmedLine.startsWith("internal class ")) {
                currentClass = extractClassName(trimmedLine)
                inTest = false
            }

            // Поиск тестовых методов
            val isTestMethod = isTestMethodLine(trimmedLine)

            if (isTestMethod) {
                // Сохраняем предыдущий тест
                if (inTest && testName.isNotEmpty()) {
                    chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), testStartLine))
                }

                // Начинаем новый тест
                testName = extractTestName(trimmedLine)
                testContent = StringBuilder(trimmedLine + "\n")
                inTest = true
                testStartLine = lineNumber
            } else if (inTest) {
                testContent.appendLine(line)
            }
        }

        // Добавляем последний тест
        if (inTest && testName.isNotEmpty()) {
            chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), testStartLine))
        }

        return chunks
    }

    private fun parseJavascriptTests(file: File, content: String): List<TestChunk> {
        val chunks = mutableListOf<TestChunk>()

        // Паттерны для различных фреймворков тестирования
        val patterns = listOf(
            """(describe|it|test)\s*\(['"`](.*?)['"`]""".toRegex(RegexOption.IGNORE_CASE),
            """test\(['"`](.*?)['"`]""".toRegex(RegexOption.IGNORE_CASE),
            """it\(['"`](.*?)['"`]""".toRegex(RegexOption.IGNORE_CASE),
            """describe\.[a-zA-Z]+\s*\(['"`](.*?)['"`]""".toRegex(RegexOption.IGNORE_CASE)
        )

        val lines = content.lines()

        patterns.forEach { regex ->
            val matches = regex.findAll(content)

            matches.forEach { match ->
                val testType = match.groupValues.getOrElse(1) { "test" }
                val testName = match.groupValues.last()
                val startIndex = match.range.first
                val endIndex = findClosingBracket(content, startIndex)

                if (endIndex > startIndex) {
                    val testBody = content.substring(startIndex, endIndex + 1)
                    // Находим номер строки
                    val lineNumber = content.substring(0, startIndex).count { it == '\n' } + 1

                    val chunk = TestChunk(
                        id = "${file.nameWithoutExtension}_${testName.hashCode().absoluteValue}_${System.currentTimeMillis()}",
                        filePath = file.path,
                        testName = "$testType: $testName",
                        content = testBody,
                        metadata = mapOf(
                            "type" to testType,
                            "language" to file.extension,
                            "framework" to when {
                                testType.equals("describe", ignoreCase = true) -> "Jest/Mocha/Jasmine"
                                testType.equals("it", ignoreCase = true) -> "Jest/Mocha/Jasmine"
                                testType.equals("test", ignoreCase = true) -> "Jest/Vitest"
                                else -> "Unknown"
                            },
                            "line" to lineNumber.toString()
                        )
                    )
                    chunks.add(chunk)
                }
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
        var testStartLine = 0

        for ((index, line) in lines.withIndex()) {
            val trimmedLine = line.trim()
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }.length

            if (trimmedLine.startsWith("class ")) {
                currentClass = extractPythonClassName(trimmedLine)
                inTest = false
            }

            val isTestMethod = isPythonTestMethod(trimmedLine)

            if (isTestMethod) {
                if (inTest && testName.isNotEmpty()) {
                    chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), testStartLine))
                }

                testName = extractPythonTestName(trimmedLine)
                testContent = StringBuilder(trimmedLine + "\n")
                inTest = true
                indentLevel = currentIndent
                testStartLine = index + 1
            } else if (inTest) {
                // Проверяем, не закончился ли тест
                if (currentIndent <= indentLevel && trimmedLine.isNotEmpty() &&
                    !trimmedLine.startsWith("#") && !trimmedLine.startsWith("@") &&
                    !trimmedLine.startsWith("def ")) {
                    // Конец теста
                    chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), testStartLine))
                    inTest = false
                } else {
                    testContent.appendLine(line)
                }
            }
        }

        // Добавляем последний тест, если он не был закрыт
        if (inTest && testName.isNotEmpty()) {
            chunks.add(createTestChunk(file, currentClass, testName, testContent.toString(), testStartLine))
        }

        return chunks
    }

    private fun extractClassName(line: String): String {
        return line.substringAfter("class ")
            .substringBefore("(")
            .substringBefore("{")
            .substringBefore(":")
            .substringBefore("<")
            .substringBefore(" ") // Для случая "class Test : Something"
            .trim()
    }

    private fun extractPythonClassName(line: String): String {
        return line.substringAfter("class ")
            .substringBefore("(")
            .substringBefore(":")
            .trim()
    }

    private fun isTestMethodLine(line: String): Boolean {
        return line.contains("@Test") ||
                line.matches(".*fun\\s+test.*".toRegex()) ||
                line.matches(".*void\\s+test.*".toRegex()) ||
                line.matches(".*public\\s+void\\s+test.*".toRegex()) ||
                line.matches(".*private\\s+void\\s+test.*".toRegex()) ||
                line.matches(".*protected\\s+void\\s+test.*".toRegex()) ||
                line.matches(".*internal\\s+fun\\s+test.*".toRegex()) ||
                line.matches(".*private\\s+fun\\s+test.*".toRegex()) ||
                line.matches(".*public\\s+fun\\s+test.*".toRegex())
    }

    private fun isPythonTestMethod(line: String): Boolean {
        return line.startsWith("def test_") ||
                (line.startsWith("def ") && line.contains("test") && line.endsWith("):"))
    }

    private fun extractTestName(line: String): String {
        return when {
            line.contains("fun ") -> {
                line.substringAfter("fun ")
                    .substringBefore("(")
                    .substringBefore(":")
                    .trim()
            }
            line.contains("void ") -> {
                line.substringAfter("void ")
                    .substringBefore("(")
                    .trim()
            }
            line.contains("def ") -> {
                line.substringAfter("def ")
                    .substringBefore("(")
                    .trim()
            }
            line.contains("@Test") -> {
                // Ищем имя метода после @Test
                val nextPart = line.substringAfter("@Test")
                when {
                    nextPart.contains("fun ") -> nextPart.substringAfter("fun ").substringBefore("(").trim()
                    nextPart.contains("void ") -> nextPart.substringAfter("void ").substringBefore("(").trim()
                    else -> {
                        // Пытаемся найти следующую строку с объявлением метода
                        "test_method_${line.hashCode().absoluteValue}"
                    }
                }
            }
            else -> "unnamed_test_${line.hashCode().absoluteValue}"
        }
    }

    private fun extractPythonTestName(line: String): String {
        return line.substringAfter("def ")
            .substringBefore("(")
            .trim()
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
            content = content.take(2000), // Ограничиваем размер
            metadata = mapOf(
                "className" to className,
                "line" to lineNumber.toString(),
                "fileSize" to file.length().toString(),
                "language" to file.extension,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
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
                        // Если это JavaScript/TypeScript, проверяем также фигурные скобки
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