package com.dshnative.app.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    val tool_calls: List<ApiToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null
)

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall
)

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String = ""
)

@Serializable
data class ApiToolDef(
    val type: String = "function",
    val function: ApiFunctionDef
)

@Serializable
data class ApiFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<ApiToolDef>? = null,
    val stream: Boolean = true,
    val max_tokens: Int? = null
)

@Serializable
data class ChatChunk(
    val id: String? = null,
    val choices: List<ChunkChoice>? = null
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    val finish_reason: String? = null
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    val tool_calls: List<ChunkToolCall>? = null
)

@Serializable
data class ChunkToolCall(
    val index: Int = 0,
    val id: String? = null,
    val function: ChunkFunction? = null
)

@Serializable
data class ChunkFunction(
    val name: String? = null,
    val arguments: String? = null
)