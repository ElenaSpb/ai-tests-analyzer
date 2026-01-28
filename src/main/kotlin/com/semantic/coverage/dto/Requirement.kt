// models.kt
package com.semantic.coverage.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class Requirement @JsonCreator constructor(
    @JsonProperty("id")
    val id: String,

    @JsonProperty("title")
    val title: String,

    @JsonProperty("description")
    val description: String,

    @JsonProperty("category")
    val category: String = "general",

    @JsonProperty("priority")
    val priority: String = "medium",

    @JsonProperty("embedding", required = false)
    val embedding: FloatArray? = null
) {
    // equals и hashCode для FloatArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Requirement

        if (id != other.id) return false
        if (title != other.title) return false
        if (description != other.description) return false
        if (category != other.category) return false
        if (priority != other.priority) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Requirement(id='$id', title='$title', description='$description', category='$category', priority='$priority')"
    }
}