package com.psqlquerytester.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val error: ErrorResponse? = null
)

@Serializable
data class Choice(
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ErrorResponse(
    val message: String,
    val type: String? = null,
    val code: String? = null
)

@Serializable
data class QueryOption(
    val name: String,
    val query: String,
    val formattedQuery: String = "",
    val parameters: List<QueryParameter> = emptyList()
)

@Serializable
data class ExtractedQuery(
    val query: String = "",
    val formattedQuery: String = "",
    val parameters: List<QueryParameter> = emptyList(),
    val error: String? = null,
    val multipleQueries: Boolean = false,
    val options: List<QueryOption> = emptyList()
)

@Serializable
data class QueryParameter(
    val name: String,
    val type: String = "text",
    val position: Int = 0,
    val originalVariable: String = ""
)

@Serializable
data class OptimizationSuggestion(
    val query: String = "",
    val formattedQuery: String = "",
    val explanation: String = "",
    val expectedImprovement: String = "",
    val parameters: List<QueryParameter> = emptyList()
)

@Serializable
data class OptimizationResponse(
    val suggestions: List<OptimizationSuggestion> = emptyList(),
    val indexSuggestions: List<String> = emptyList(),
    val analysis: String = "",
    val error: String? = null
)

@Serializable
data class CodeChangeResponse(
    val modifiedCode: String = "",
    val explanation: String = "",
    val error: String? = null
)

@Serializable
data class ParameterValueQuery(
    val query: String = "",
    val explanation: String = "",
    val error: String? = null
)
