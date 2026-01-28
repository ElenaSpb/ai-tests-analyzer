package com.semantic.coverage

// parser.kt
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.walk

@OptIn(ExperimentalPathApi::class)
class TestParser {
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
        val projectPath = Path(projectRoot)

        testPatterns.forEach { pattern ->
            projectPath.walk()
                .filter { it.fileName.toString().matches(pattern.toRegex()) }
                .take(maxFiles)
                .forEach { file ->
                    val chunks = extractChunksFromFile(file.toFile())
                    testChunks.addAll(chunks)
                }
        }

        return testChunks.take(maxFiles * 5) // ~5 чанков на файл
    }

    private fun extractChunksFromFile(file: File): List<TestChunk> {
        val content = file.readText(Charsets.UTF_8)
        val chunks = mutableListOf<TestChunk>()

        when (file.extension.lowercase()) {
            "kt", "java" -> chunks.addAll(parseKotlinJavaTests(file, content))
            "js", "jsx", "ts", "tsx" -> chunks.addAll(parseJavascriptTests(file, content))
            "py" -> chunks.addAll(parsePythonTests(file, content))
            else -> {
                // Общий парсер для других языков
                val chunk = TestChunk(
                    id = "${file.nameWithoutExtension}_general",
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

        for (line in lines) {
            lineNumber++
            val trimmedLine = line.trim()

            // Поиск объявления класса
            if (trimmedLine.startsWith("class ") || trimmedLine.startsWith("@Test class ")) {
                currentClass = trimmedLine.substringAfter("class ").substringBefore(" ").substringBefore(":")
                inTest = false
            }

            // Поиск тестовых методов
            if (trimmedLine.contains("@Test") ||
                trimmedLine.startsWith("fun test") ||
                trimmedLine.startsWith("def test") ||
                trimmedLine.startsWith("void test")) {

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

    private fun parseJavascriptTests(file: File, content: String): List<TestChunk> {
        val chunks = mutableListOf<TestChunk>()
        val regex = """(describe|it|test)\s*\(['"](.*?)['"]""".toRegex()
        val matches = regex.findAll(content)

        matches.forEach { match ->
            val testType = match.groupValues[1]
            val testName = match.groupValues[2]
            val startIndex = match.range.first
            val endIndex = findClosingBracket(content, startIndex)

            if (endIndex > startIndex) {
                val testBody = content.substring(startIndex, endIndex + 1)
                val chunk = TestChunk(
                    id = "${file.nameWithoutExtension}_${testName.hashCode()}",
                    filePath = file.path,
                    testName = "$testType: $testName",
                    content = testBody,
                    metadata = mapOf(
                        "type" to testType,
                        "language" to "javascript"
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
        return TestChunk(
            id = "${file.name}_${className}_${testName}".hashCode().toString(),
            filePath = file.path,
            testName = if (className.isNotEmpty()) "$className.$testName" else testName,
            content = content.take(2000), // Ограничиваем размер
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
            else -> line.substringAfter("@Test").trim()
        }
    }

    private fun findClosingBracket(content: String, startIndex: Int): Int {
        var count = 0
        for (i in startIndex until content.length) {
            when (content[i]) {
                '(' -> count++
                ')' -> {
                    count--
                    if (count == 0) return i
                }
            }
        }
        return -1
    }
}