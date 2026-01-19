package com.psqlquerytester.api

import com.psqlquerytester.settings.CredentialManager
import com.psqlquerytester.settings.PluginSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class OpenRouterClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun extractQuery(
        codeSnippet: String,
        language: String = "elixir",
        dbSchema: String? = null,
        surroundingContext: String = ""
    ): ExtractedQuery {
        val apiKey = CredentialManager.getOpenRouterApiKey()
            ?: return ExtractedQuery(
                query = "",
                error = "OpenRouter API key not configured. Please set it in Settings > PSQL Query Tester."
            )

        val schemaSection = if (dbSchema != null) {
            """

IMPORTANT: Use ONLY the tables and columns from this database schema. If the code references a table or column that doesn't exist, note it in the error field.

$dbSchema
"""
        } else ""

        val contextSection = if (surroundingContext.isNotBlank()) {
            """

SURROUNDING CODE CONTEXT (for understanding imports, module structure, variable definitions):
```$language
$surroundingContext
```

The user has selected the following specific code snippet from the above context. Focus on extracting the SQL from the SELECTED CODE, but use the surrounding context to understand variable types, imports, and module structure.
"""
        } else ""

        val systemPrompt = """
You are a PostgreSQL expert that extracts SQL queries from code. Your task is to:
1. Identify SQL queries in the provided code
2. Extract and format them as clean PostgreSQL queries
3. Detect any parameters/variables that need to be filled in
4. Convert language-specific placeholders to PostgreSQL ${'$'}1, ${'$'}2, etc. format
5. VALIDATE that all table and column names exist in the provided database schema
6. Use the surrounding context to understand variable types, imports, and function definitions
$schemaSection

IMPORTANT: Be decisive. Never explain your reasoning in the response - just return the JSON.

If there is ONE query, return:
{
    "query": "The raw SQL query with ${'$'}1, ${'$'}2, etc. for parameters",
    "formattedQuery": "The same query but nicely formatted with proper indentation",
    "parameters": [
        {
            "name": "descriptive_name",
            "type": "postgresql_type (text, integer, boolean, timestamp, uuid, jsonb, etc.)",
            "position": 1,
            "originalVariable": "original variable name from code"
        }
    ]
}

If there are MULTIPLE queries, return them as options for the user to choose:
{
    "multipleQueries": true,
    "options": [
        {
            "name": "Short descriptive name (e.g. function name or purpose)",
            "query": "The raw SQL query",
            "formattedQuery": "Formatted version",
            "parameters": [...]
        }
    ]
}

For Elixir/Ecto queries:
- Convert Ecto.Query syntax to raw SQL
- Map ^variable to positional parameters
- Handle fragment() calls
- Convert Ecto types to PostgreSQL types
- Use context to determine correct table names from schema aliases

If you cannot find a SQL query in the code, return:
{"query": "", "formattedQuery": "", "parameters": [], "error": "No SQL query found in the selected code"}

If the query references tables/columns not in the schema, return:
{"query": "", "formattedQuery": "", "parameters": [], "error": "Table or column not found: [name]. Available tables: [list]"}
""".trimIndent()

        val userPrompt = """
$contextSection
SELECTED CODE to extract SQL from:

```$language
$codeSnippet
```
""".trimIndent()

        val request = ChatCompletionRequest(
            model = PluginSettings.getInstance().getModel(),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            maxTokens = 2000,
            temperature = 0.1
        )

        return try {
            val response: ChatCompletionResponse = client.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://github.com/psql-query-tester")
                header("X-Title", "PSQL Query Tester")
                setBody(request)
            }.body()

            if (response.error != null) {
                return ExtractedQuery(
                    query = "",
                    error = "API Error: ${response.error.message}"
                )
            }

            val content = response.choices.firstOrNull()?.message?.content
                ?: return ExtractedQuery(query = "", error = "No response from API")

            // Parse the JSON response
            parseExtractedQuery(content)

        } catch (e: Exception) {
            ExtractedQuery(
                query = "",
                error = "Failed to extract query: ${e.message}"
            )
        }
    }

    private fun parseExtractedQuery(content: String): ExtractedQuery {
        return try {
            // Clean up the response - remove any markdown code blocks if present
            val cleanContent = content
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            json.decodeFromString<ExtractedQuery>(cleanContent)
        } catch (e: Exception) {
            // Try to extract query manually if JSON parsing fails
            ExtractedQuery(
                query = content,
                formattedQuery = content,
                error = "Could not parse structured response. Raw content shown above."
            )
        }
    }

    suspend fun suggestOptimizations(
        query: String,
        executionTimeMs: Long,
        dbSchema: String?,
        parameters: List<QueryParameter>
    ): OptimizationResponse {
        val apiKey = CredentialManager.getOpenRouterApiKey()
            ?: return OptimizationResponse(error = "OpenRouter API key not configured.")

        val schemaSection = dbSchema ?: "Schema not available"

        val systemPrompt = """
You are a PostgreSQL performance optimization expert. Analyze the given query and suggest optimizations.

DATABASE SCHEMA:
$schemaSection

Return ONLY a valid JSON object (no markdown, no code blocks) with this structure:
{
    "analysis": "Brief analysis of the current query's performance characteristics",
    "suggestions": [
        {
            "query": "The optimized SQL query with ${'$'}1, ${'$'}2, etc. for parameters",
            "formattedQuery": "The same query nicely formatted",
            "explanation": "What this optimization does and why it's faster",
            "expectedImprovement": "e.g., '2-3x faster' or 'reduces full table scan'",
            "parameters": [
                {"name": "param_name", "type": "type", "position": 1, "originalVariable": ""}
            ]
        }
    ],
    "indexSuggestions": [
        "CREATE INDEX idx_name ON table(column) -- explanation"
    ]
}

Consider these optimization strategies:
1. Add appropriate indexes (suggest CREATE INDEX statements)
2. Rewrite JOINs for better performance
3. Use EXISTS instead of IN for subqueries
4. Add LIMIT if appropriate
5. Use covering indexes
6. Optimize WHERE clause order
7. Consider partitioning suggestions
8. Use EXPLAIN ANALYZE insights

Provide 1-3 query alternatives, ordered by expected improvement.
Keep the same parameters (${'$'}1, ${'$'}2, etc.) as the original query.
""".trimIndent()

        val userPrompt = """
Optimize this PostgreSQL query:

```sql
$query
```

Current execution time: ${executionTimeMs}ms
Parameters: ${parameters.map { "${it.name} (${it.type})" }.joinToString(", ")}

Suggest faster alternatives and any helpful indexes.
""".trimIndent()

        val request = ChatCompletionRequest(
            model = PluginSettings.getInstance().getModel(),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            maxTokens = 3000,
            temperature = 0.2
        )

        return try {
            val response: ChatCompletionResponse = client.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://github.com/psql-query-tester")
                header("X-Title", "PSQL Query Tester")
                setBody(request)
            }.body()

            if (response.error != null) {
                return OptimizationResponse(error = "API Error: ${response.error.message}")
            }

            val content = response.choices.firstOrNull()?.message?.content
                ?: return OptimizationResponse(error = "No response from API")

            parseOptimizationResponse(content)
        } catch (e: Exception) {
            OptimizationResponse(error = "Failed to get optimizations: ${e.message}")
        }
    }

    private fun parseOptimizationResponse(content: String): OptimizationResponse {
        return try {
            val cleanContent = content
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            json.decodeFromString<OptimizationResponse>(cleanContent)
        } catch (e: Exception) {
            OptimizationResponse(error = "Could not parse optimization response: ${e.message}")
        }
    }

    suspend fun generateCodeChange(
        originalCode: String,
        originalQuery: String,
        optimizedQuery: String,
        language: String,
        surroundingContext: String = ""
    ): CodeChangeResponse {
        val apiKey = CredentialManager.getOpenRouterApiKey()
            ?: return CodeChangeResponse(error = "OpenRouter API key not configured.")

        val contextSection = if (surroundingContext.isNotBlank()) {
            """

SURROUNDING CODE CONTEXT (for understanding imports, module structure, and coding style):
```$language
$surroundingContext
```

Use this context to understand the coding style, imports, and patterns used in this codebase.
"""
        } else ""

        val systemPrompt = """
You are an expert $language developer. Your task is to update code to use an optimized SQL query.

Return ONLY a valid JSON object (no markdown, no code blocks) with this structure:
{
    "modifiedCode": "The complete modified code with the optimized query",
    "explanation": "Brief explanation of the changes made"
}

Rules:
- Keep the same code style and conventions as the original
- Only change the SQL query, preserve all other logic
- Maintain the same variable names and structure
- For Elixir/Ecto: convert raw SQL back to Ecto syntax if possible, or use fragment()
- Ensure the code is syntactically correct
- Match the indentation and formatting style of the surrounding code
- The modifiedCode should be a drop-in replacement for the original code selection
""".trimIndent()

        val userPrompt = """
$contextSection
Update this $language code to use the optimized query:

CODE TO MODIFY (this is what will be replaced):
```$language
$originalCode
```

ORIGINAL QUERY (extracted from the code):
```sql
$originalQuery
```

OPTIMIZED QUERY (to use instead):
```sql
$optimizedQuery
```

Generate the modified code that can directly replace the original code selection.
""".trimIndent()

        val request = ChatCompletionRequest(
            model = PluginSettings.getInstance().getModel(),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            maxTokens = 3000,
            temperature = 0.1
        )

        return try {
            val response: ChatCompletionResponse = client.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://github.com/psql-query-tester")
                header("X-Title", "PSQL Query Tester")
                setBody(request)
            }.body()

            if (response.error != null) {
                return CodeChangeResponse(error = "API Error: ${response.error.message}")
            }

            val content = response.choices.firstOrNull()?.message?.content
                ?: return CodeChangeResponse(error = "No response from API")

            parseCodeChangeResponse(content)
        } catch (e: Exception) {
            CodeChangeResponse(error = "Failed to generate code: ${e.message}")
        }
    }

    private fun parseCodeChangeResponse(content: String): CodeChangeResponse {
        return try {
            val cleanContent = content
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            json.decodeFromString<CodeChangeResponse>(cleanContent)
        } catch (e: Exception) {
            CodeChangeResponse(error = "Could not parse code response: ${e.message}")
        }
    }

    suspend fun generateParameterValueQuery(
        paramName: String,
        paramType: String,
        description: String,
        dbSchema: String?
    ): ParameterValueQuery {
        val apiKey = CredentialManager.getOpenRouterApiKey()
            ?: return ParameterValueQuery(error = "OpenRouter API key not configured.")

        val schemaSection = dbSchema ?: "Schema not available"

        val systemPrompt = """
You are a PostgreSQL expert. Generate a SQL query to find a value based on the user's description.

DATABASE SCHEMA:
$schemaSection

Return ONLY a valid JSON object (no markdown, no code blocks) with this structure:
{
    "query": "SELECT query that returns a single value",
    "explanation": "Brief explanation of what the query does"
}

Rules:
1. The query MUST return exactly ONE row with ONE column
2. Use LIMIT 1 if needed to ensure a single result
3. The returned value should be of type: $paramType
4. If the request is ambiguous or impossible, return an error field instead

If unable to generate a valid query:
{"query": "", "explanation": "", "error": "Reason why the query cannot be generated"}
""".trimIndent()

        val userPrompt = """
Generate a PostgreSQL query to find a value for parameter "$paramName" (type: $paramType).

User's description: $description

The query should return exactly one value that matches this description.
""".trimIndent()

        val request = ChatCompletionRequest(
            model = PluginSettings.getInstance().getModel(),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            maxTokens = 1000,
            temperature = 0.1
        )

        return try {
            val response: ChatCompletionResponse = client.post("https://openrouter.ai/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("HTTP-Referer", "https://github.com/psql-query-tester")
                header("X-Title", "PSQL Query Tester")
                setBody(request)
            }.body()

            if (response.error != null) {
                return ParameterValueQuery(error = "API Error: ${response.error.message}")
            }

            val content = response.choices.firstOrNull()?.message?.content
                ?: return ParameterValueQuery(error = "No response from API")

            parseParameterValueQuery(content)
        } catch (e: Exception) {
            ParameterValueQuery(error = "Failed to generate query: ${e.message}")
        }
    }

    private fun parseParameterValueQuery(content: String): ParameterValueQuery {
        return try {
            val cleanContent = content
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            json.decodeFromString<ParameterValueQuery>(cleanContent)
        } catch (e: Exception) {
            ParameterValueQuery(error = "Could not parse response: ${e.message}")
        }
    }

    fun close() {
        client.close()
    }
}
