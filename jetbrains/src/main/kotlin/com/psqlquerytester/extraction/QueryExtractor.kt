package com.psqlquerytester.extraction

import com.psqlquerytester.api.ExtractedQuery
import com.psqlquerytester.api.OpenRouterClient
import com.psqlquerytester.db.SchemaIntrospector

class QueryExtractor {

    private val client = OpenRouterClient()

    suspend fun extract(codeSnippet: String, language: String = "elixir"): ExtractedQuery {
        if (codeSnippet.isBlank()) {
            return ExtractedQuery(
                query = "",
                error = "No code selected. Please select code containing a SQL query."
            )
        }

        // Fetch database schema for context
        val dbSchema = SchemaIntrospector.getSchema()

        return client.extractQuery(codeSnippet, language, dbSchema)
    }

    /**
     * Detect the programming language from file extension or content
     */
    fun detectLanguage(fileName: String?, content: String): String {
        // First try file extension
        fileName?.let { name ->
            return when {
                name.endsWith(".ex") || name.endsWith(".exs") -> "elixir"
                name.endsWith(".py") -> "python"
                name.endsWith(".ts") || name.endsWith(".tsx") -> "typescript"
                name.endsWith(".js") || name.endsWith(".jsx") -> "javascript"
                name.endsWith(".kt") || name.endsWith(".kts") -> "kotlin"
                name.endsWith(".java") -> "java"
                name.endsWith(".rb") -> "ruby"
                name.endsWith(".go") -> "go"
                name.endsWith(".rs") -> "rust"
                name.endsWith(".php") -> "php"
                name.endsWith(".sql") -> "sql"
                else -> detectFromContent(content)
            }
        }

        return detectFromContent(content)
    }

    private fun detectFromContent(content: String): String {
        return when {
            // Elixir patterns
            content.contains("defmodule") ||
            content.contains("Ecto.Query") ||
            content.contains("|>") ||
            content.contains("def ") && content.contains("do") -> "elixir"

            // Python patterns
            content.contains("def ") && content.contains(":") ||
            content.contains("import ") && !content.contains("import {") -> "python"

            // TypeScript/JavaScript patterns
            content.contains("const ") || content.contains("let ") ||
            content.contains("function ") || content.contains("=> {") -> "javascript"

            // SQL patterns (raw SQL)
            content.uppercase().let { upper ->
                upper.contains("SELECT ") ||
                upper.contains("INSERT INTO") ||
                upper.contains("UPDATE ") ||
                upper.contains("DELETE FROM")
            } -> "sql"

            else -> "unknown"
        }
    }

    fun close() {
        client.close()
    }
}
