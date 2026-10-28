package com.dshnative.app.model

import kotlinx.serialization.Serializable

/** 聊天消息模型 */
@Serializable
data class ChatMessage(
    val role: String,        // "user" / "assistant" / "tool"
    val content: String,
    val toolCalls: List<ToolCall> = emptyList()
)

/** 模型请求的工具调用 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/** 会话 */
@Serializable
data class Conversation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)