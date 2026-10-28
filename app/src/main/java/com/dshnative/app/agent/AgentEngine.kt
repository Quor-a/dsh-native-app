package com.dshnative.app.agent

import com.dshnative.app.llm.ApiMessage
import com.dshnative.app.llm.ApiToolCall
import com.dshnative.app.llm.LlmClient
import com.dshnative.app.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent 引擎：工具调用循环。
 * 模型请求工具 -> 原生执行 -> 结果回填 -> 继续，直到模型给出最终回复。
 */
class AgentEngine(
    private val llm: LlmClient,
    private val tools: ToolRegistry
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val systemPrompt = """
        你是运行在 Android 手机上的原生 AI 助手（DshNative）。你直接运行在手机上，不是网页应用。
        你拥有操控手机的能力（工具）：
        - android_ui_tree：读取当前屏幕 UI 树（文本/坐标/可点击性）
        - android_screenshot：截图（返回 base64，你可以分析图片）
        - android_ui_click：点击（按文本或坐标）
        - android_ui_input：输入文本
        - android_ui_swipe：滑动/滚动
        - android_ui_global：home/back/recents 等全局动作
        - android_shell_exec：执行 shell 命令（需要 root 或 Shizuku）
        - android_app_list：列出已安装应用

        使用原则：
        1. 用户让"打开/点击/查看"某应用或内容时，先 android_ui_tree 或 android_screenshot 了解当前屏幕。
        2. 需要点击时优先用文本点击；文本定位不到再用坐标（从 UI 树获取）。
        3. 操作后再次调用 android_ui_tree 确认结果，必要时继续操作。
        4. 截图返回 base64 时，说明你看到的内容（如"屏幕上显示微信主界面"）。
        5. 保持回答简洁，用中文。
    """.trimIndent()

    /**
     * 运行 Agent。
     * @param history 历史消息（含本次用户消息）
     * @param onDelta 流式输出最终回复增量
     * @param onToolStart 工具开始回调（工具名）
     * @return 最终回复全文
     */
    suspend fun run(
        history: List<ApiMessage>,
        onDelta: (String) -> Unit = {},
        onToolStart: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val messages = history.toMutableList()
        var finalContent = ""
        // 最多 8 轮工具循环，防止死循环
        for (round in 1..8) {
            val result = llm.chat(messages, tools.definitions())
            if (result.content.isNotEmpty()) {
                finalContent += result.content
                onDelta(result.content)
            }
            if (result.toolCalls.isEmpty()) {
                return@withContext finalContent
            }
            // 记录 assistant 的工具调用
            messages.add(
                ApiMessage(
                    role = "assistant",
                    content = result.content.ifEmpty { null },
                    tool_calls = result.toolCalls.map {
                        ApiToolCall(it.id, "function", it.function)
                    }
                )
            )
            // 执行每个工具，结果回填
            for (tc in result.toolCalls) {
                val name = tc.function.name
                val args = tc.function.arguments
                onToolStart(name)
                val toolResult = try {
                    tools.execute(name, args)
                } catch (e: Exception) {
                    """{"error":"工具执行异常: ${e.message}"}"""
                }
                // 如果模型没给 tool_call_id，用工具名+索引兜底
                val callId = tc.id.ifEmpty { "call_${name}_$round" }
                messages.add(
                    ApiMessage(
                        role = "tool",
                        content = toolResult,
                        tool_call_id = callId,
                        name = name
                    )
                )
            }
        }
        finalContent.ifEmpty { "已达到最大工具调用轮次" }
    }
}