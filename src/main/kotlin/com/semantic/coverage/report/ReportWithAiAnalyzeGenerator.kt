package com.semantic.coverage.report

import com.semantic.coverage.dto.ConfidenceLevel
import com.semantic.coverage.analyze.CoverageReport
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReportWithAiAnalyzeGenerator {

    fun generateHtmlReport(reports: List<CoverageReport>, outputPath: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val totalRequirements = reports.size
        val avgCoverage = reports.map { it.coverageScore }.average()
        val requirementsWithHighConfidence = reports.count { it.confidence == ConfidenceLevel.HIGH }
        val requirementsWithLowCoverage = reports.count { it.coverageScore < 30 }
        val totalAiAnalyses = reports.count { it.aiAnalysis != null }
        val requirementsWithCriteria = reports.count { it.requirement.acceptanceCriteria.isNotEmpty() }
        val requirementsWithTags = reports.count { it.requirement.tags.isNotEmpty() }

        val html = buildString {
            appendLine("""
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Semantic Coverage Report - Brain-up/brn</title>
<style>
:root {
--primary-color: #2c3e50;
--secondary-color: #3498db;
--success-color: #27ae60;
--warning-color: #f39c12;
--danger-color: #e74c3c;
--info-color: #17a2b8;
--light-bg: #f8f9fa;
--dark-bg: #343a40;
--border-color: #dee2e6;
--criteria-bg: #e8f5e9;
--tags-bg: #e3f2fd;
}
* {
margin: 0;
padding: 0;
box-sizing: border-box;
}
body {
font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
line-height: 1.6;
color: #333;
background-color: #f5f7fa;
padding: 20px;
}
.container {
max-width: 1400px;
margin: 0 auto;
}
.header {
background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
color: white;
padding: 30px;
border-radius: 10px;
margin-bottom: 30px;
box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}
.header h1 {
font-size: 2.5rem;
margin-bottom: 10px;
}
.header .subtitle {
font-size: 1.1rem;
opacity: 0.9;
}
.stats-grid {
display: grid;
grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
gap: 20px;
margin-bottom: 30px;
}
.stat-card {
background: white;
padding: 25px;
border-radius: 8px;
box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
text-align: center;
transition: transform 0.2s;
}
.stat-card:hover {
transform: translateY(-5px);
box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);
}
.stat-value {
font-size: 2.5rem;
font-weight: bold;
margin-bottom: 5px;
}
.stat-label {
color: #6c757d;
font-size: 0.9rem;
}
.coverage-high { color: var(--success-color); }
.coverage-medium { color: var(--warning-color); }
.coverage-low { color: var(--danger-color); }
.requirement-card {
background: white;
border-radius: 8px;
padding: 25px;
margin-bottom: 25px;
box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
border-left: 5px solid var(--border-color);
}
.requirement-card.high-coverage {
border-left-color: var(--success-color);
}
.requirement-card.medium-coverage {
border-left-color: var(--warning-color);
}
.requirement-card.low-coverage {
border-left-color: var(--danger-color);
}
.requirement-header {
display: flex;
justify-content: space-between;
align-items: flex-start;
margin-bottom: 20px;
flex-wrap: wrap;
gap: 15px;
}
.requirement-title {
flex: 1;
min-width: 300px;
}
.requirement-title h2 {
color: var(--primary-color);
margin-bottom: 5px;
font-size: 1.5rem;
}
.requirement-meta {
display: flex;
gap: 10px;
margin-bottom: 10px;
flex-wrap: wrap;
}
.requirement-id {
display: inline-block;
background: var(--light-bg);
padding: 3px 10px;
border-radius: 4px;
font-size: 0.9rem;
}
.requirement-category {
display: inline-block;
background: #e9ecef;
padding: 3px 8px;
border-radius: 4px;
font-size: 0.85rem;
text-transform: capitalize;
}
.requirement-priority {
display: inline-block;
padding: 3px 8px;
border-radius: 4px;
font-size: 0.85rem;
font-weight: bold;
}
.priority-high { background: #ffebee; color: #c62828; }
.priority-medium { background: #fff8e1; color: #5d4037; }
.priority-low { background: #e8f5e9; color: #2e7d32; }
.coverage-badge {
display: inline-flex;
align-items: center;
padding: 8px 16px;
border-radius: 20px;
font-weight: bold;
font-size: 1.1rem;
}
.confidence-badge {
display: inline-block;
padding: 5px 12px;
border-radius: 12px;
font-size: 0.85rem;
font-weight: 600;
margin-left: 10px;
}
.confidence-high { background: #d4edda; color: #155724; }
.confidence-medium { background: #fff3cd; color: #856404; }
.confidence-low { background: #f8d7da; color: #721c24; }
.requirement-description {
background: var(--light-bg);
padding: 15px;
border-radius: 6px;
margin: 15px 0;
line-height: 1.7;
}
.criteria-section {
background: var(--criteria-bg);
padding: 15px;
border-radius: 6px;
margin: 15px 0;
border-left: 4px solid var(--success-color);
}
.criteria-section h4 {
color: #2e7d32;
margin-bottom: 10px;
display: flex;
align-items: center;
gap: 8px;
}
.criteria-section h4::before {
content: "✅";
}
.criteria-list {
list-style: none;
padding-left: 0;
}
.criteria-list li {
padding: 8px 0 8px 25px;
position: relative;
border-bottom: 1px dashed #c8e6c9;
}
.criteria-list li:last-child {
border-bottom: none;
}
.criteria-list li::before {
content: "✓";
position: absolute;
left: 0;
color: var(--success-color);
font-weight: bold;
}
.tags-section {
background: var(--tags-bg);
padding: 12px 15px;
border-radius: 6px;
margin: 15px 0;
display: flex;
flex-wrap: wrap;
gap: 8px;
}
.tag {
background: rgba(33, 150, 243, 0.15);
color: #1565c0;
padding: 4px 10px;
border-radius: 12px;
font-size: 0.85rem;
}
.matches-section {
margin: 25px 0;
}
.matches-section h3 {
color: var(--primary-color);
margin-bottom: 15px;
display: flex;
align-items: center;
gap: 10px;
}
.matches-section h3::before {
content: "🔍";
}
.matches-grid {
display: grid;
grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
gap: 15px;
}
.match-card {
background: white;
border: 1px solid var(--border-color);
border-radius: 6px;
padding: 18px;
transition: all 0.2s;
}
.match-card:hover {
border-color: var(--secondary-color);
box-shadow: 0 4px 8px rgba(52, 152, 219, 0.1);
}
.match-header {
display: flex;
justify-content: space-between;
align-items: center;
margin-bottom: 10px;
}
.match-name {
font-weight: 600;
color: var(--primary-color);
font-size: 1.05rem;
}
.match-similarity {
font-weight: bold;
color: var(--secondary-color);
}
.match-file {
color: #6c757d;
font-size: 0.9rem;
margin-bottom: 8px;
font-family: 'Consolas', monospace;
}
.match-snippet {
background: #f8f9fa;
padding: 10px;
border-radius: 4px;
font-family: 'Consolas', monospace;
font-size: 0.85rem;
max-height: 150px;
overflow-y: auto;
margin-top: 10px;
border-left: 3px solid var(--border-color);
}
.ai-analysis-section {
background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
color: white;
padding: 25px;
border-radius: 8px;
margin: 25px 0;
}
.ai-analysis-section h3 {
display: flex;
align-items: center;
gap: 10px;
margin-bottom: 20px;
}
.ai-analysis-section h3::before {
content: "🤖";
}
.ai-analysis-grid {
display: grid;
grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
gap: 20px;
margin-top: 20px;
}
.ai-aspect-card {
background: rgba(255, 255, 255, 0.1);
padding: 20px;
border-radius: 6px;
backdrop-filter: blur(10px);
}
.ai-aspect-card h4 {
margin-bottom: 15px;
color: white;
font-size: 1.1rem;
display: flex;
align-items: center;
gap: 8px;
}
.ai-aspect-list {
list-style: none;
}
.ai-aspect-list li {
padding: 8px 0;
border-bottom: 1px solid rgba(255, 255, 255, 0.1);
display: flex;
align-items: center;
}
.ai-aspect-list li:last-child {
border-bottom: none;
}
.ai-aspect-list li::before {
content: "•";
margin-right: 10px;
font-size: 1.5rem;
}
.ai-explanation {
background: rgba(255, 255, 255, 0.1);
padding: 20px;
border-radius: 6px;
margin-top: 20px;
line-height: 1.7;
}
.gaps-section {
background: #fff8e1;
padding: 20px;
border-radius: 6px;
margin: 20px 0;
border-left: 4px solid var(--warning-color);
}
.gaps-section h3 {
color: #856404;
margin-bottom: 15px;
display: flex;
align-items: center;
gap: 10px;
}
.gaps-section h3::before {
content: "⚠️";
}
.gaps-list {
list-style: none;
}
.gaps-list li {
padding: 10px 0;
border-bottom: 1px solid rgba(133, 100, 4, 0.1);
display: flex;
align-items: flex-start;
gap: 10px;
}
.gaps-list li:last-child {
border-bottom: none;
}
.gaps-list li::before {
content: "🔸";
flex-shrink: 0;
}
.details-section {
margin: 20px 0;
}
details {
background: white;
border: 1px solid var(--border-color);
border-radius: 6px;
margin: 10px 0;
overflow: hidden;
}
summary {
padding: 15px;
background: var(--light-bg);
cursor: pointer;
font-weight: 600;
display: flex;
align-items: center;
justify-content: space-between;
}
summary:hover {
background: #e9ecef;
}
summary::after {
content: "▶";
transition: transform 0.3s;
margin-left: 10px;
}
details[open] summary::after {
transform: rotate(90deg);
}
.details-content {
padding: 15px;
border-top: 1px solid var(--border-color);
max-height: 400px;
overflow-y: auto;
font-family: 'Consolas', monospace;
font-size: 0.9rem;
line-height: 1.5;
white-space: pre-wrap;
background: #f8f9fa;
}
.footer {
text-align: center;
margin-top: 50px;
padding: 20px;
color: #6c757d;
font-size: 0.9rem;
border-top: 1px solid var(--border-color);
}
@media (max-width: 768px) {
.stats-grid {
grid-template-columns: 1fr;
}
.matches-grid {
grid-template-columns: 1fr;
}
.ai-analysis-grid {
grid-template-columns: 1fr;
}
.requirement-header {
flex-direction: column;
align-items: stretch;
}
.requirement-title {
min-width: auto;
}
}
</style>
</head>
<body>
<div class="container">
""".trimIndent())

            // Header
            appendLine("""
<div class="header">
<h1>🧠 Semantic Coverage Report</h1>
<div class="subtitle">
<p>Проект: Brain-up/brn | Сгенерировано: $timestamp</p>
<p>AI-powered анализ покрытия тестами бизнес-требований с критериями приемки</p>
</div>
</div>
""".trimIndent())

            // Statistics Grid
            appendLine("""
<div class="stats-grid">
<div class="stat-card">
<div class="stat-value">$totalRequirements</div>
<div class="stat-label">Всего требований</div>
</div>
<div class="stat-card">
<div class="stat-value coverage-high">${"%.1f".format(avgCoverage)}%</div>
<div class="stat-label">Среднее покрытие</div>
</div>
<div class="stat-card">
<div class="stat-value">$requirementsWithHighConfidence</div>
<div class="stat-label">Высокая уверенность</div>
</div>
<div class="stat-card">
<div class="stat-value coverage-low">$requirementsWithLowCoverage</div>
<div class="stat-label">Низкое покрытие (<30%)</div>
</div>
<div class="stat-card">
<div class="stat-value">$requirementsWithCriteria</div>
<div class="stat-label">С критериями приемки</div>
</div>
<div class="stat-card">
<div class="stat-value">$totalAiAnalyses</div>
<div class="stat-label">AI анализов</div>
</div>
</div>
""".trimIndent())

            // Requirements List
            reports.forEachIndexed { index, report ->
                val coverageClass = when {
                    report.coverageScore >= 70 -> "high-coverage"
                    report.coverageScore >= 40 -> "medium-coverage"
                    else -> "low-coverage"
                }
                val confidenceClass = when (report.confidence) {
                    ConfidenceLevel.HIGH -> "confidence-high"
                    ConfidenceLevel.MEDIUM -> "confidence-medium"
                    ConfidenceLevel.LOW -> "confidence-low"
                }
                val priorityClass = when (report.requirement.priority.lowercase()) {
                    "high" -> "priority-high"
                    "medium" -> "priority-medium"
                    "low" -> "priority-low"
                    else -> "priority-medium"
                }

                appendLine("""
<div class="requirement-card $coverageClass">
<div class="requirement-header">
<div class="requirement-title">
<div class="requirement-meta">
<span class="requirement-id">${report.requirement.id}</span>
<span class="requirement-category">${report.requirement.category}</span>
<span class="requirement-priority $priorityClass">${report.requirement.priority.uppercase()}</span>
</div>
<h2>${index + 1}. ${escapeHtml(report.requirement.title)}</h2>
</div>
<div>
<span class="coverage-badge ${getCoverageColorClass(report.coverageScore)}">
${"%.1f".format(report.coverageScore)}%
</span>
<span class="confidence-badge $confidenceClass">
${report.confidence.toString().lowercase().replaceFirstChar { it.uppercase() }}
</span>
</div>
</div>
<div class="requirement-description">
${escapeHtml(report.requirement.description)}
</div>
""".trimIndent())

                // Criteria Section
                if (report.requirement.acceptanceCriteria.isNotEmpty()) {
                    appendLine("""
<div class="criteria-section">
<h4>Критерии приемки (${report.requirement.acceptanceCriteria.size})</h4>
<ul class="criteria-list">
${report.requirement.acceptanceCriteria.joinToString("") { criterion ->
                        "<li>${escapeHtml(criterion)}</li>"
                    }}
</ul>
</div>
""".trimIndent())
                }

                // Tags Section
                if (report.requirement.tags.isNotEmpty()) {
                    appendLine("""
<div class="tags-section">
${report.requirement.tags.joinToString("") { tag ->
                        """<span class="tag">${escapeHtml(tag)}</span>"""
                    }}
</div>
""".trimIndent())
                }

                // AI Analysis Section
                report.aiAnalysis?.let { aiAnalysis ->
                    appendLine("""
<div class="ai-analysis-section">
<h3>AI Analysis</h3>
<div class="ai-explanation">
<strong>Общий вывод:</strong> ${escapeHtml(aiAnalysis.explanation)}
</div>
<div class="ai-analysis-grid">
<div class="ai-aspect-card">
<h4>✅ Покрытые аспекты</h4>
${if (aiAnalysis.coveredAspects.isNotEmpty()) {
                        """
<ul class="ai-aspect-list">
${aiAnalysis.coveredAspects.joinToString("") { "<li>${escapeHtml(it)}</li>" }}
</ul>
""".trimIndent()
                    } else {
                        "<p style='opacity: 0.8;'>Нет данных</p>"
                    }}
</div>
<div class="ai-aspect-card">
<h4>❌ Непокрытые аспекты</h4>
${if (aiAnalysis.missingAspects.isNotEmpty()) {
                        """
<ul class="ai-aspect-list">
${aiAnalysis.missingAspects.joinToString("") { "<li>${escapeHtml(it)}</li>" }}
</ul>
""".trimIndent()
                    } else {
                        "<p style='opacity: 0.8;'>Нет данных</p>"
                    }}
</div>
<div class="ai-aspect-card">
<h4>🎯 Рекомендации</h4>
${if (aiAnalysis.recommendations.isNotEmpty()) {
                        """
<ul class="ai-aspect-list">
${aiAnalysis.recommendations.joinToString("") { "<li>${escapeHtml(it)}</li>" }}
</ul>
""".trimIndent()
                    } else {
                        "<p style='opacity: 0.8;'>Нет данных</p>"
                    }}
</div>
</div>
""".trimIndent())

                    // AI Criteria Analysis
                    if (aiAnalysis.coveredCriteria.isNotEmpty() || aiAnalysis.missingCriteria.isNotEmpty()) {
                        appendLine("""
<div class="ai-analysis-grid" style="margin-top: 15px;">
<div class="ai-aspect-card">
<h4>✅ Покрытые критерии</h4>
${if (aiAnalysis.coveredCriteria.isNotEmpty()) {
                            """
<ul class="ai-aspect-list">
${aiAnalysis.coveredCriteria.joinToString("") { "<li>${escapeHtml(it)}</li>" }}
</ul>
""".trimIndent()
                        } else {
                            "<p style='opacity: 0.8;'>Нет покрытых критериев</p>"
                        }}
</div>
<div class="ai-aspect-card">
<h4>❌ Непокрытые критерии</h4>
${if (aiAnalysis.missingCriteria.isNotEmpty()) {
                            """
<ul class="ai-aspect-list">
${aiAnalysis.missingCriteria.joinToString("") { "<li>${escapeHtml(it)}</li>" }}
</ul>
""".trimIndent()
                        } else {
                            "<p style='opacity: 0.8;'>Все критерии покрыты</p>"
                        }}
</div>
</div>
""".trimIndent())
                    }

                    appendLine("""
<details>
<summary>Полный AI анализ (JSON)</summary>
<div class="details-content">
${escapeHtml(aiAnalysis.rawText)}
</div>
</details>
</div>
""".trimIndent())
                }

                // Matches Section
                if (report.matches.isNotEmpty()) {
                    appendLine("""
<div class="matches-section">
<h3>Найденные соответствия (${report.matches.size})</h3>
<div class="matches-grid">
""".trimIndent())

                    report.matches.take(5).forEach { match ->
                        val matchConfidenceClass = when (match.confidence) {
                            ConfidenceLevel.HIGH -> "confidence-high"
                            ConfidenceLevel.MEDIUM -> "confidence-medium"
                            ConfidenceLevel.LOW -> "confidence-low"
                        }
                        appendLine("""
<div class="match-card">
<div class="match-header">
<div class="match-name">${escapeHtml(match.testChunk.testName)}</div>
<div class="match-similarity">
${"%.3f".format(match.similarityScore)}
<span class="$matchConfidenceClass confidence-badge" style="margin-left: 5px; padding: 2px 8px; font-size: 0.8rem;">
${match.confidence.toString().lowercase().replaceFirstChar { it.uppercase() }}
</span>
</div>
</div>
<div class="match-file">
${escapeHtml(File(match.testChunk.filePath).name)}
</div>
<div class="match-snippet">
${escapeHtml(match.testChunk.content.take(200))}${if (match.testChunk.content.length > 200) "..." else ""}
</div>
<details style="margin-top: 10px;">
<summary>Доказательства соответствия</summary>
<div class="details-content">
${escapeHtml(match.evidence)}
</div>
</details>
</div>
""".trimIndent())
                    }

                    if (report.matches.size > 5) {
                        appendLine("""
<div class="match-card" style="text-align: center; padding: 30px; background: #f8f9fa;">
<p>... и еще ${report.matches.size - 5} соответствий</p>
</div>
""".trimIndent())
                    }

                    appendLine("""
</div>
</div>
""".trimIndent())
                } else {
                    appendLine("""
<div class="matches-section">
<h3>Соответствия</h3>
<p style="text-align: center; padding: 20px; color: #6c757d; background: #f8f9fa; border-radius: 6px;">
❌ Не найдено соответствующих тестов для этого требования
</p>
</div>
""".trimIndent())
                }

                // Gaps Section
                if (report.gaps.isNotEmpty()) {
                    appendLine("""
<div class="gaps-section">
<h3>Выявленные пробелы в покрытии (${report.gaps.size})</h3>
<ul class="gaps-list">
${report.gaps.joinToString("") { "<li>${escapeHtml(it)}</li>" }}
</ul>
</div>
""".trimIndent())
                }

                // Details Section
                appendLine("""
<div class="details-section">
<details>
<summary>Детальная информация и метрики</summary>
<div class="details-content">
ID требования: ${report.requirement.id}
Категория: ${report.requirement.category}
Приоритет: ${report.requirement.priority}
Количество критериев приемки: ${report.requirement.acceptanceCriteria.size}
Количество тегов: ${report.requirement.tags.size}
Время анализа: ${report.timestamp}
---
Статистика покрытия:
• Количество соответствий: ${report.matches.size}
• Максимальное сходство: ${report.matches.maxOfOrNull { it.similarityScore }?.let { "%.3f".format(it) } ?: "0.000"}
• Среднее сходство: ${if (report.matches.isNotEmpty()) "%.3f".format(report.matches.map { it.similarityScore }.average()) else "0.000"}
• Уровень уверенности: ${report.confidence}
---
ТЕСТЫ (${report.matches.size}):
${report.matches.joinToString("\n\n") { match ->
                    """
====== ТЕСТ: ${match.testChunk.testName} ======
Файл: ${match.testChunk.filePath}
Сходство: ${"%.3f".format(match.similarityScore)}
Уверенность: ${match.confidence}
Метаданные: ${match.testChunk.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}
""".trimIndent()
                }}
</div>
</details>
</div>
""".trimIndent())

                appendLine("</div>") // Close requirement-card
            }

            // Footer
            appendLine("""
<div class="footer">
<p>Generated by Semantic Coverage System v2.0</p>
<p>AI-powered test requirement traceability with acceptance criteria analysis</p>
<p style="margin-top: 10px; font-size: 0.8rem;">
Проект Brain-up/brn | https://brainup.site | https://github.com/Brain-up/brn
</p>
<p style="margin-top: 5px; font-size: 0.75rem; color: #adb5bd;">
Отчет сгенерирован: $timestamp | Требований: $totalRequirements | Среднее покрытие: ${"%.1f".format(avgCoverage)}%
</p>
</div>
</div>
</body>
</html>
""".trimIndent())
        }

        File(outputPath).writeText(html, Charsets.UTF_8)
        println("✅ HTML отчет сохранен: $outputPath")
    }

    fun generateConsoleReport(reports: List<CoverageReport>) {
        println("\n" + "=".repeat(80))
        println("📊 SEMANTIC COVERAGE REPORT - CONSOLE VERSION")
        println("=".repeat(80))
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        println("Время генерации: $timestamp")
        println("Всего требований: ${reports.size}")
        val avgCoverage = reports.map { it.coverageScore }.average()
        println("Среднее покрытие: ${"%.1f".format(avgCoverage)}%")
        println("\n" + "-".repeat(80))

        reports.forEachIndexed { index, report ->
            val coverageEmoji = when {
                report.coverageScore >= 70 -> "🟢"
                report.coverageScore >= 40 -> "🟡"
                else -> "🔴"
            }
            val confidenceEmoji = when (report.confidence) {
                ConfidenceLevel.HIGH -> "🔵"
                ConfidenceLevel.MEDIUM -> "🟠"
                ConfidenceLevel.LOW -> "⚫"
            }
            val priorityEmoji = when (report.requirement.priority.lowercase()) {
                "high" -> "❗"
                "medium" -> "🔸"
                "low" -> "▫️"
                else -> "🔸"
            }

            println("\n${index + 1}. ${report.requirement.id}: ${report.requirement.title}")
            println("   ${priorityEmoji} Приоритет: ${report.requirement.priority.uppercase()} | Категория: ${report.requirement.category}")
            println("   ${coverageEmoji} Coverage: ${"%.1f".format(report.coverageScore)}%")
            println("   ${confidenceEmoji} Confidence: ${report.confidence}")

            // Краткое описание
            val desc = report.requirement.description.take(80)
            if (desc.length < report.requirement.description.length) {
                println("   📝 $desc...")
            } else {
                println("   📝 $desc")
            }

            // Критерии приемки
            if (report.requirement.acceptanceCriteria.isNotEmpty()) {
                println("   ✅ Критерии приемки: ${report.requirement.acceptanceCriteria.size}")
                report.requirement.acceptanceCriteria.take(2).forEach { criterion ->
                    println("      • ${criterion.take(60)}${if (criterion.length > 60) "..." else ""}")
                }
                if (report.requirement.acceptanceCriteria.size > 2) {
                    println("      ... и еще ${report.requirement.acceptanceCriteria.size - 2}")
                }
            }

            // Теги
            if (report.requirement.tags.isNotEmpty()) {
                val tagsDisplay = report.requirement.tags.take(4).joinToString(", ")
                println("   🏷️  Теги: $tagsDisplay${if (report.requirement.tags.size > 4) " и еще ${report.requirement.tags.size - 4}" else ""}")
            }

            // AI анализ (кратко)
            report.aiAnalysis?.let { ai ->
                val aiConfidence = when (ai.confidence) {
                    ConfidenceLevel.HIGH -> "🔵"
                    ConfidenceLevel.MEDIUM -> "🟠"
                    ConfidenceLevel.LOW -> "⚫"
                }
                println("   🤖 AI: ${aiConfidence} ${ai.explanation.take(70)}...")
                if (ai.missingCriteria.isNotEmpty()) {
                    println("      ❌ Непокрытые критерии: ${ai.missingCriteria.size}")
                }
            }

            // Соответствия
            if (report.matches.isNotEmpty()) {
                val topMatch = report.matches.first()
                println("   🔍 Top match: ${topMatch.testChunk.testName} (${"%.3f".format(topMatch.similarityScore)})")
                if (report.matches.size > 1) {
                    println("      + еще ${report.matches.size - 1} соответствий")
                }
            } else {
                println("   ❌ No matches found")
            }

            // Пробелы
            if (report.gaps.isNotEmpty()) {
                println("   ⚠️  Gaps: ${report.gaps.first()}")
                if (report.gaps.size > 1) {
                    println("      + еще ${report.gaps.size - 1} пробелов")
                }
            }

            if (index < reports.size - 1) {
                println("   " + "-".repeat(40))
            }
        }

        println("\n" + "=".repeat(80))
        println("📈 SUMMARY STATISTICS:")
        println("-".repeat(80))

        val highCoverage = reports.count { it.coverageScore >= 70 }
        val mediumCoverage = reports.count { it.coverageScore in 40.0..69.9 }
        val lowCoverage = reports.count { it.coverageScore < 40 }

        val highConfidence = reports.count { it.confidence == ConfidenceLevel.HIGH }
        val mediumConfidence = reports.count { it.confidence == ConfidenceLevel.MEDIUM }
        val lowConfidence = reports.count { it.confidence == ConfidenceLevel.LOW }

        val withCriteria = reports.count { it.requirement.acceptanceCriteria.isNotEmpty() }
        val withTags = reports.count { it.requirement.tags.isNotEmpty() }

        println("Coverage Distribution:")
        println("  🟢 High (≥70%): $highCoverage требований (${percentage(highCoverage, reports.size)}%)")
        println("  🟡 Medium (40-69%): $mediumCoverage требований (${percentage(mediumCoverage, reports.size)}%)")
        println("  🔴 Low (<40%): $lowCoverage требований (${percentage(lowCoverage, reports.size)}%)")

        println("\nConfidence Distribution:")
        println("  🔵 High: $highConfidence требований (${percentage(highConfidence, reports.size)}%)")
        println("  🟠 Medium: $mediumConfidence требований (${percentage(mediumConfidence, reports.size)}%)")
        println("  ⚫ Low: $lowConfidence требований (${percentage(lowConfidence, reports.size)}%)")

        println("\nRequirement Metadata:")
        println("  ✅ С критериями приемки: $withCriteria требований (${percentage(withCriteria, reports.size)}%)")
        println("  🏷️  С тегами: $withTags требований (${percentage(withTags, reports.size)}%)")

        val aiAnalyses = reports.count { it.aiAnalysis != null }
        if (aiAnalyses > 0) {
            println("\n🤖 AI Analysis: $aiAnalyses требований проанализировано AI (${percentage(aiAnalyses, reports.size)}%)")
        }

        // Рекомендации
        println("\n" + "-".repeat(80))
        println("🎯 RECOMMENDATIONS:")

        val lowCoverageReports = reports.filter { it.coverageScore < 30 }
        if (lowCoverageReports.isNotEmpty()) {
            println("1. Приоритетные требования для улучшения покрытия:")
            lowCoverageReports.take(3).forEach { report ->
                println("   • ${report.requirement.id}: ${report.requirement.title} (${"%.1f".format(report.coverageScore)}%)")
            }
        }

        val noMatchesReports = reports.filter { it.matches.isEmpty() }
        if (noMatchesReports.isNotEmpty()) {
            println("\n2. Требования без тестов:")
            noMatchesReports.take(3).forEach { report ->
                println("   • ${report.requirement.id}: ${report.requirement.title}")
            }
        }

        val uncoveredCriteriaReports = reports.filter { report ->
            report.requirement.acceptanceCriteria.isNotEmpty() &&
                    report.aiAnalysis?.missingCriteria?.isNotEmpty() == true
        }
        if (uncoveredCriteriaReports.isNotEmpty()) {
            println("\n3. Требования с непокрытыми критериями приемки:")
            uncoveredCriteriaReports.take(3).forEach { report ->
                val missingCount = report.aiAnalysis?.missingCriteria?.size ?: 0
                println("   • ${report.requirement.id}: ${missingCount} непокрытых критериев")
            }
        }

        println("\n" + "=".repeat(80))
        println("✅ Отчет сгенерирован успешно")
        println("   Для детального отчета смотрите HTML версию")
        println("=".repeat(80))
    }

    fun generateJsonReport(reports: List<CoverageReport>, outputPath: String) {
        val reportData = mapOf(
            "timestamp" to LocalDateTime.now().toString(),
            "totalRequirements" to reports.size,
            "averageCoverage" to reports.map { it.coverageScore }.average(),
            "requirementsWithCriteria" to reports.count { it.requirement.acceptanceCriteria.isNotEmpty() },
            "requirementsWithTags" to reports.count { it.requirement.tags.isNotEmpty() },
            "reports" to reports.map { report ->
                mapOf(
                    "requirement" to mapOf(
                        "id" to report.requirement.id,
                        "title" to report.requirement.title,
                        "description" to report.requirement.description,
                        "category" to report.requirement.category,
                        "priority" to report.requirement.priority,
                        "acceptanceCriteria" to report.requirement.acceptanceCriteria,
                        "tags" to report.requirement.tags
                    ),
                    "coverageScore" to report.coverageScore,
                    "confidence" to report.confidence.toString(),
                    "matchesCount" to report.matches.size,
                    "matches" to report.matches.take(5).map { match ->
                        mapOf(
                            "testName" to match.testChunk.testName,
                            "filePath" to match.testChunk.filePath,
                            "similarityScore" to match.similarityScore,
                            "confidence" to match.confidence.toString()
                        )
                    },
                    "gaps" to report.gaps,
                    "aiAnalysis" to report.aiAnalysis?.let { ai ->
                        mapOf(
                            "confidence" to ai.confidence.toString(),
                            "coveredAspects" to ai.coveredAspects,
                            "missingAspects" to ai.missingAspects,
                            "coveredCriteria" to ai.coveredCriteria,
                            "missingCriteria" to ai.missingCriteria,
                            "explanation" to ai.explanation,
                            "recommendations" to ai.recommendations
                        )
                    },
                    "timestamp" to report.timestamp
                )
            }
        )

        val json = com.fasterxml.jackson.databind.ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(reportData)

        File(outputPath).writeText(json, Charsets.UTF_8)
        println("✅ JSON отчет сохранен: $outputPath")
    }

    // Вспомогательные методы
    private fun getCoverageColorClass(score: Float): String {
        return when {
            score >= 70 -> "coverage-high"
            score >= 40 -> "coverage-medium"
            else -> "coverage-low"
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun percentage(part: Int, total: Int): String {
        return if (total > 0) {
            "%.1f".format(part * 100.0 / total)
        } else {
            "0.0"
        }
    }

    // Генерация диаграммы покрытия (текстовая)
    fun generateCoverageChart(reports: List<CoverageReport>) {
        println("\n📊 COVERAGE DISTRIBUTION CHART:")
        println("-".repeat(60))

        val buckets = mutableMapOf(
            "90-100%" to 0,
            "80-89%" to 0,
            "70-79%" to 0,
            "60-69%" to 0,
            "50-59%" to 0,
            "40-49%" to 0,
            "30-39%" to 0,
            "20-29%" to 0,
            "10-19%" to 0,
            "0-9%" to 0
        )

        reports.forEach { report ->
            val bucket = when (val score = report.coverageScore.toInt()) {
                in 90..100 -> "90-100%"
                in 80..89 -> "80-89%"
                in 70..79 -> "70-79%"
                in 60..69 -> "60-69%"
                in 50..59 -> "50-59%"
                in 40..49 -> "40-49%"
                in 30..39 -> "30-39%"
                in 20..29 -> "20-29%"
                in 10..19 -> "10-19%"
                else -> "0-9%"
            }
            buckets[bucket] = buckets[bucket]!! + 1
        }

        val maxCount = buckets.values.maxOrNull() ?: 0
        val scale = if (maxCount > 0) 50.0 / maxCount else 0.0

        buckets.forEach { (range, count) ->
            val barLength = (count * scale).toInt()
            val bar = "█".repeat(barLength)
            val percentage = if (reports.isNotEmpty()) {
                "%.1f".format(count * 100.0 / reports.size)
            } else {
                "0.0"
            }
            println("$range: ${bar.padEnd(50)} $count треб. ($percentage%)")
        }

        println("-".repeat(60))
    }
}