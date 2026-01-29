// python does not start
package com.semantic.coverage.embedding

import java.io.*
import java.nio.file.Files
import kotlin.math.sqrt

class SentenceTransformerPythonService : EmbeddingService {
    private val dimension = 384
    private val modelName = "sentence-transformers/all-MiniLM-L6-v2"
    private var process: Process? = null
    private var isRunning = false

    init {
        startPythonProcess()
    }

    private fun startPythonProcess() {
        try {
            // Проверяем, есть ли Python
            val pythonCheck = ProcessBuilder("python", "--version").start()
            pythonCheck.waitFor()

            if (pythonCheck.exitValue() != 0) {
                println("Python not found or not working")
                isRunning = false
                return
            }

            // Создаем Python скрипт
            val pythonScript = """
import sys
import json
import traceback

try:
    from sentence_transformers import SentenceTransformer
    import numpy as np
    
    model = SentenceTransformer('$modelName')
    print(json.dumps({"status": "ready"}))
    sys.stdout.flush()
    
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            data = json.loads(line)
            texts = data["texts"]
            embeddings = model.encode(texts).tolist()
            print(json.dumps({"embeddings": embeddings}))
            sys.stdout.flush()
        except Exception as e:
            print(json.dumps({"error": str(e)}))
            sys.stdout.flush()
            
except Exception as e:
    print(json.dumps({"error": f"Initialization failed: {str(e)}"}))
    sys.stdout.flush()
    sys.exit(1)
            """.trimIndent()

            // Создаем временный файл
            val scriptFile = Files.createTempFile("embedding_", ".py")
            Files.write(scriptFile, pythonScript.toByteArray())

            // Запускаем процесс
            val pb = ProcessBuilder("python", scriptFile.toString())
            pb.redirectErrorStream(true)
            process = pb.start()

            // Читаем статус инициализации
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            val statusLine = reader.readLine()

            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val status = mapper.readValue(statusLine, Map::class.java)

            if (status["status"] == "ready") {
                isRunning = true
                println("✅ SentenceTransformer service started successfully")
            } else {
                println("Failed to initialize SentenceTransformer: $status")
                isRunning = false
                process?.destroy()
            }

        } catch (e: Exception) {
            println("Failed to start SentenceTransformer: ${e.message}")
            isRunning = false
            process?.destroy()
        }
    }

    override fun getEmbedding(text: String): FloatArray {
        if (!isRunning) {
            throw IllegalStateException("Python process not running")
        }

        return try {
            getEmbeddings(listOf(text)).firstOrNull() ?: FloatArray(dimension) { 0f }
        } catch (e: Exception) {
            println("⚠️  Failed to get embedding: ${e.message}")
            throw e
        }
    }

    private fun getEmbeddings(texts: List<String>): List<FloatArray> {
        if (!isRunning || process == null) {
            throw IllegalStateException("Python process not running")
        }

        return try {
            // Отправляем запрос
            val request = mapOf("texts" to texts)
            val writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            writer.write(com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request) + "\n")
            writer.flush()

            // Читаем ответ с таймаутом
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            val responseLine = reader.readLine() ?: throw IOException("No response from Python")

            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val response = mapper.readValue(responseLine, Map::class.java)

            if (response["error"] != null) {
                throw Exception("Python error: ${response["error"]}")
            }

            @Suppress("UNCHECKED_CAST")
            val embeddings = response["embeddings"] as List<List<Double>>

            embeddings.map { list ->
                FloatArray(list.size) { i -> list[i].toFloat() }
            }

        } catch (e: Exception) {
            println("⚠️  SentenceTransformer failed: ${e.message}")
            // Перезапускаем процесс
            restartProcess()
            throw e
        }
    }

    private fun restartProcess() {
        println("🔄 Restarting Python process...")
        process?.destroy()
        process = null
        isRunning = false
        startPythonProcess()
    }

    override fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must have same dimension" }

        if (vec1.isEmpty() || vec2.isEmpty()) {
            return 0.0f
        }

        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in vec1.indices) {
            val v1 = vec1[i].toDouble()
            val v2 = vec2[i].toDouble()
            dotProduct += v1 * v2
            norm1 += v1 * v1
            norm2 += v2 * v2
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) {
            (dotProduct / denominator).toFloat()
        } else {
            0.0f
        }
    }

    fun isRunning(): Boolean = isRunning

    fun close() {
        process?.destroy()
        isRunning = false
    }
}