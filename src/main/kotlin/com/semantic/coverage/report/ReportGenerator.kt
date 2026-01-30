package com.semantic.coverage.report

import com.semantic.coverage.dto.ConfidenceLevel
import com.semantic.coverage.analyze.CoverageReport
import java.io.File

class ReportGenerator {

    fun generateHtmlReport(reports: List<CoverageReport>, outputPath: String) {
        val html = buildString {
            append("""
            <!DOCTYPE html>
            <html lang="ru">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Semantic Coverage Report</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; }
                    .header { background: #2c3e50; color: white; padding: 20px; border-radius: 5px; }
                    .requirement { border: 1px solid #ddd; margin: 10px 0; padding: 15px; border-radius: 5px; }
                    .coverage-high { background: #d4edda; border-color: #c3e6cb; }
                    .coverage-medium { background: #fff3cd; border-color: #ffeaa7; }
                    .coverage-low { background: #f8d7da; border-color: #f5c6cb; }
                    .score { font-size: 24px; font-weight: bold; margin: 10px 0; }
                    .match { background: #f8f9fa; margin: 5px 0; padding: 10px; border-left: 4px solid #007bff; }
                    .gap { color: #dc3545; margin: 5px 0; padding: 5px; background: #ffe6e6; }
                    table { width: 100%; border-collapse: collapse; margin: 20px 0; }
                    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    th { background-color: #f2f2f2; }
                    .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 20px 0; }
                    .summary-card { background: white; padding: 15px; border-radius: 5px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>📊 Semantic Coverage Report</h1>
                    <p>Generated: ${java.time.LocalDateTime.now()}</p>
                </div>
                
                <div class="summary">
                    <div class="summary-card">
                        <h3>Всего требований</h3>
                        <p class="score">${reports.size}</p>
                    </div>
                    <div class="summary-card">
                        <h3>Среднее покрытие</h3>
                        <p class="score">${"%.1f".format(reports.map { it.coverageScore }.average())}%</p>
                    </div>
                    <div class="summary-card">
                        <h3>Требования без покрытия</h3>
                        <p class="score">${reports.count { it.coverageScore < 30 }}</p>
                    </div>
                </div>
            """)

            reports.forEachIndexed { index, report ->
                val coverageClass = when {
                    report.coverageScore >= 70 -> "coverage-high"
                    report.coverageScore >= 40 -> "coverage-medium"
                    else -> "coverage-low"
                }

                append("""
                <div class="requirement $coverageClass">
                    <h2>${index + 1}. ${report.requirement.title} [${report.requirement.id}]</h2>
                    <p><strong>Описание:</strong> ${report.requirement.description}</p>
                    <p class="score">Coverage: ${"%.1f".format(report.coverageScore)}%</p>
                    <p><strong>Confidence:</strong> ${report.confidence}</p>
                    
                    <h3>Соответствующие тесты:</h3>
                """)

                if (report.matches.isEmpty()) {
                    append("<p>Не найдено соответствий</p>")
                } else {
                    append("<table>")
                    append("""
                        <tr>
                            <th>Тест</th>
                            <th>Файл</th>
                            <th>Сходство</th>
                            <th>Confidence</th>
                            <th>Доказательства</th>
                        </tr>
                    """)

                    report.matches.take(5).forEach { match ->
                        append("""
                        <tr>
                            <td>${match.testChunk.testName}</td>
                            <td>${File(match.testChunk.filePath).name}</td>
                            <td>${"%.3f".format(match.similarityScore)}</td>
                            <td>${match.confidence}</td>
                            <td><small>${match.evidence.take(100)}...</small></td>
                        </tr>
                        """)
                    }
                    append("</table>")
                }

                if (report.gaps.isNotEmpty()) {
                    append("<h3>⚠️ Разрывы покрытия:</h3>")
                    report.gaps.forEach { gap ->
                        append("<div class='gap'>$gap</div>")
                    }
                }

                append("</div>")
            }

            append("""
                <footer style="margin-top: 40px; padding-top: 20px; border-top: 1px solid #ddd;">
                    <p>Report generated by Semantic Coverage System</p>
                    <p>AI-powered test requirement traceability tool</p>
                </footer>
            </body>
            </html>
            """)
        }

        File(outputPath).writeText(html)
    }

    fun generateConsoleReport(reports: List<CoverageReport>) {
        println("=".repeat(80))
        println("SEMANTIC COVERAGE REPORT")
        println("Generated: ${java.time.LocalDateTime.now()}")
        println("=".repeat(80))

        reports.forEachIndexed { index, report ->
            println("\n${index + 1}. ${report.requirement.id}: ${report.requirement.title}")
            println("   Description: ${report.requirement.description}")
            println("   Coverage: ${"%.1f".format(report.coverageScore)}% | Confidence: ${report.confidence}")

            if (report.matches.isNotEmpty()) {
                println("   Top matches:")
                report.matches.take(3).forEach { match ->
                    println("   - ${match.testChunk.testName}")
                    println("     File: ${File(match.testChunk.filePath).name}")
                    println("     Similarity: ${"%.3f".format(match.similarityScore)} | Confidence: ${match.confidence}")
                }
            }

            if (report.gaps.isNotEmpty()) {
                println("   Gaps identified:")
                report.gaps.forEach { gap ->
                    println("   ⚠️  $gap")
                }
            }

            println("-".repeat(60))
        }

        val avgCoverage = reports.map { it.coverageScore }.average()
        val lowCoverageCount = reports.count { it.coverageScore < 30 }

        println("\nSUMMARY:")
        println("  Total requirements: ${reports.size}")
        println("  Average coverage: ${"%.1f".format(avgCoverage)}%")
        println("  Requirements with low coverage (<30%): $lowCoverageCount")
        println("  Requirements with high confidence: ${reports.count { it.confidence == ConfidenceLevel.HIGH }}")
    }
}