package com.dshnative.app.tools

import android.content.Context
import android.content.pm.PackageManager
import com.dshnative.app.llm.ApiFunctionDef
import com.dshnative.app.llm.ApiToolDef
import com.dshnative.app.service.DshAccessibilityService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Android 工具注册表：把手机原生能力暴露给 LLM。
 * 每个工具含 JSON Schema 定义 + 执行器（通过无障碍服务/Shell 实现）。
 */
class ToolRegistry(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 工具定义（JSON Schema） */
    fun definitions(): List<ApiToolDef> = listOf(
        toolDef(
            "android_ui_tree",
            "获取当前屏幕的可访问性 UI 树（JSON 格式），包含控件类名/文本/坐标/可点击性。查看屏幕内容时先调用它。"
        ),
        toolDef(
            "android_screenshot",
            "截取当前屏幕，返回 base64 编码的 PNG 图片和宽高。需要分析视觉内容时调用。"
        ),
        toolDef(
            "android_ui_click",
            "点击屏幕元素。可以用文本精确点击（优先），或用坐标点击。参数：text（可选，要点击的文本）或 x,y（坐标）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("text", buildJsonObject { put("type", "string"); put("description", "要点击的可见文本") })
                        put("x", buildJsonObject { put("type", "integer"); put("description", "横坐标像素") })
                        put("y", buildJsonObject { put("type", "integer"); put("description", "纵坐标像素") })
                    }
                )
            )
        ),
        toolDef(
            "android_ui_input",
            "向当前聚焦的输入框输入文本。参数：text（要输入的完整字符串）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("text", buildJsonObject { put("type", "string"); put("description", "要输入的文本") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("text")))
                )
            )
        ),
        toolDef(
            "android_ui_swipe",
            "在屏幕上滑动（用于滚动列表、翻页、下拉）。参数：x1,y1 起点，x2,y2 终点，duration_ms 持续时间（默认 300）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("x1", buildJsonObject { put("type", "integer") })
                        put("y1", buildJsonObject { put("type", "integer") })
                        put("x2", buildJsonObject { put("type", "integer") })
                        put("y2", buildJsonObject { put("type", "integer") })
                        put("duration_ms", buildJsonObject { put("type", "integer") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("x1"), JsonPrimitive("y1"), JsonPrimitive("x2"), JsonPrimitive("y2")))
                )
            )
        ),
        toolDef(
            "android_ui_global",
            "执行全局系统动作。参数：action，可选 home（主页）、back（返回）、recents（最近任务）、notifications（通知栏）、quick_settings（快捷设置）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(JsonPrimitive("home"), JsonPrimitive("back"), JsonPrimitive("recents"), JsonPrimitive("notifications"), JsonPrimitive("quick_settings"))))
                        })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("action")))
                )
            )
        ),
        toolDef(
            "android_shell_exec",
            "在设备上执行 shell 命令。需要 root（su）或 Shizuku 授权。参数：command（要执行的命令）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("command", buildJsonObject { put("type", "string"); put("description", "要执行的 shell 命令") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("command")))
                )
            )
        ),
        toolDef(
            "android_app_list",
            "列出已安装的应用（包名 + 应用名）。参数：optional（可选）filter（如 system/user/all）。"
        )
    )

    private fun toolDef(name: String, description: String, parameters: JsonObject = defaultParams()): ApiToolDef {
        return ApiToolDef("function", ApiFunctionDef(name, description, parameters))
    }

    private fun defaultParams(): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))
    )

    /** 执行工具，返回 JSON 字符串结果 */
    fun execute(name: String, argumentsJson: String): String {
        val args = try {
            json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return when (name) {
            "android_ui_tree" -> service()?.getUiTreeJson() ?: errNoService()
            "android_screenshot" -> service()?.takeScreenshotJson() ?: errNoService()
            "android_ui_click" -> click(args)
            "android_ui_input" -> input(args)
            "android_ui_swipe" -> swipe(args)
            "android_ui_global" -> global(args)
            "android_shell_exec" -> shellExec(args)
            "android_app_list" -> appList(args)
            else -> """{"error":"unknown tool $name"}"""
        }
    }

    private fun click(args: JsonObject): String {
        val svc = service() ?: return errNoService()
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrEmpty()) {
            return if (svc.clickByText(text)) {
                """{"ok":true,"method":"text","text":"$text"}"""
            } else {
                """{"error":"未找到可点击的文本 '$text'"}"""
            }
        }
        val x = args["x"]?.jsonPrimitive?.intOrNull ?: return """{"error":"需要 text 或 x,y 参数"}"""
        val y = args["y"]?.jsonPrimitive?.intOrNull ?: return """{"error":"需要 x,y 参数"}"""
        return if (svc.tap(x, y)) """{"ok":true,"method":"coordinate","x":$x,"y":$y}""" else """{"error":"tap 失败"}"""
    }

    private fun input(args: JsonObject): String {
        val svc = service() ?: return errNoService()
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 text 参数"}"""
        return if (svc.inputText(text)) """{"ok":true,"input":"$text"}""" else """{"error":"输入失败（无聚焦输入框？）"}"""
    }

    private fun swipe(args: JsonObject): String {
        val svc = service() ?: return errNoService()
        val x1 = args["x1"]?.jsonPrimitive?.intOrNull ?: return """{"error":"缺少 x1"}"""
        val y1 = args["y1"]?.jsonPrimitive?.intOrNull ?: return """{"error":"缺少 y1"}"""
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull ?: return """{"error":"缺少 x2"}"""
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull ?: return """{"error":"缺少 y2"}"""
        val d = args["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 300L
        return if (svc.swipe(x1, y1, x2, y2, d)) """{"ok":true,"swipe":"($x1,$y1)->($x2,$y2)"}""" else """{"error":"swipe 失败"}"""
    }

    private fun global(args: JsonObject): String {
        val svc = service() ?: return errNoService()
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 action"}"""
        val id = when (action) {
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> null
        }
        if (id == null) return """{"error":"未知动作 $action"}"""
        return if (svc.globalAction(id)) """{"ok":true,"action":"$action"}""" else """{"error":"全局动作执行失败"}"""
    }

    private fun shellExec(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 command"}"""
        return try {
            // 优先 su（root），无 root 时返回提示（后续接入 Shizuku）
            val proc = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            } catch (_: Exception) {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            }
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            """{"exit":${proc.exitValue()},"stdout":${JsonPrimitive(out.take(4000))},"stderr":${JsonPrimitive(err.take(1000))}}"""
        } catch (e: Exception) {
            """{"error":"shell 执行失败: ${e.message}（需要 root 或 Shizuku）"}"""
        }
    }

    private fun appList(args: JsonObject): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }
        val list = apps.take(200).map {
            val label = try { pm.getApplicationLabel(it).toString() } catch (_: Exception) { it.packageName }
            """{"pkg":"${it.packageName}","label":"${label.replace("\"", "\\\"")}"}"""
        }
        return """{"count":${apps.size},"apps":[${list.joinToString(",")}]}"""
    }

    private fun service(): DshAccessibilityService? = DshAccessibilityService.instance

    private fun errNoService() = """{"error":"无障碍服务未连接，请先在系统设置中开启 DshNative 无障碍服务"}"""
}