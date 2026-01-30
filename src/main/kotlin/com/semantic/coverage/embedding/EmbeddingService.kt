package com.semantic.coverage.embedding

interface EmbeddingService {
    fun getTextEmbedding(text: String): FloatArray
    fun getCodeEmbedding(code: String): FloatArray = getTextEmbedding(code)
    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float
}