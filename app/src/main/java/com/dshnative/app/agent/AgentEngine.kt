package com.dshnative.app.agent

import android.content.Context
import com.dshnative.app.llm.ApiMessage
import com.dshnative.app.llm.ApiToolCall
import com.dshnative.app.llm.LlmClient
import com.dshnative.app.plugins.PluginRegistry
import com.dshnative.app.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent 引擎：工具调用循环。
 * 模型请求工具 -> 原生执行 -> 结果回填 -> 继续，直到模型给出最终回复。
 *
 * 系统提示词 = 基础骨架 + PluginRegistry.enabledPrompts() 动态拼接。
 * 即使外部以 [AgentEngine] 两参构造（不显式传插件注册表），
 * 也会通过应用上下文拿到全局插件注册表，让插件启停实时反映到系统提示词。
 */
class AgentEngine(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    plugins: PluginRegistry? = null
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 插件注册表：优先用外部注入，否则从应用上下文懒加载。 */
    private val pluginRegistry: PluginRegistry? = plugins ?: appContext()?.let { PluginRegistry(it) }

    /** 通过反射拿到 Application Context（不修改 ChatViewModel 构造方式）。 */
    private fun appContext(): Context? = try {
        val clazz = Class.forName("android.app.ActivityThread")
        clazz.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    private val baseSystemPrompt = """
        # 角色
        你是运行在 Android 手机上的原生 AI 助手（DshNative），直接操控手机，不是网页应用。

        # 能力（工具）
        - 屏幕操控：android_ui_tree（读屏幕 UI 树）、android_screenshot（截图）、android_ui_click（点击）、android_ui_input（输入）、android_ui_swipe（滑动）、android_ui_global（Home/Back/最近任务）
        - 终端：android_terminal_exec（内置完整 Termux bash，免 root）——安装软件、跑脚本、网络请求都可用它
        - 文件：android_file_list / android_file_read / android_file_write（应用私有目录与 /sdcard）
        - 网页：android_web_fetch（抓网页）、android_browser_open（打开浏览器）
        - 剪贴板：android_clipboard_get / android_clipboard_set
        - 系统：android_shell_exec（需 root 或 Shizuku）、android_app_list、android_system_info（电量/型号/网络）
        - 任务规划：todo_write（把多步任务写成清单并逐步更新状态）
        - 后台任务：jobs_run / jobs_list / jobs_result（长时间命令放后台执行，不阻塞对话）

        # 使用原则（必须遵守）
        1. 用户让"打开/点击/查看"某应用或内容时，先 android_ui_tree 或 android_screenshot 了解当前屏幕，再操作。
        2. 点击优先用文本定位（android_ui_click text=）；文本定位不到再用坐标（从 UI 树获取）。
        3. 操作后必须再次读取屏幕确认结果；失败就换方法，不要假装成功。
        4. 复杂任务先调用 todo_write 列出计划，每完成一步就更新状态。
        5. 长时间命令（下载/编译/循环）用 jobs_run 提交到后台，用 jobs_result 查结果。
        6. 不要编造：截图内容、文件内容、命令输出必须来自工具真实返回值；不确定就如实说"不知道"。
        7. 回答用中文，先给结论，再给必要细节，保持简洁。

        # 完成判据
        只有真实完成用户请求才算完成；工具返回错误时，向用户说明错误并给出可行的下一步建议。
    """.trimIndent()

    /**
     * 动态系统提示词 = 基础骨架 + 所有启用插件注入的能力片段。
     * 插件启用/停用会实时反映到模型看到的系统提示词。
     */
    private fun buildSystemPrompt(): String {
        val pluginPrompts = pluginRegistry?.enabledPrompts()?.trim() ?: ""
        return if (pluginPrompts.isNotEmpty()) {
            "$baseSystemPrompt\n\n# 已启用的插件能力\n$pluginPrompts"
        } else {
            baseSystemPrompt
        }
    }

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
        val messages = mutableListOf(ApiMessage("system", buildSystemPrompt()))
        messages.addAll(history)
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