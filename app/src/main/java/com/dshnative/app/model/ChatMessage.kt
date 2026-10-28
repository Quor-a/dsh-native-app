package com.dshnative.app.model

/** 聊天消息模型 */
data class ChatMessage(
    val role: String,        // "user" / "assistant" / "tool"
    val content: String,
    val toolCalls: List<ToolCall> = emptyList()
)

/** 模型请求的工具调用 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)