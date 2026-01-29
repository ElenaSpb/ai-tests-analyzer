package com.semantic.coverage.embedding

interface EmbeddingService {
    fun getEmbedding(text: String): FloatArray
    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float
}