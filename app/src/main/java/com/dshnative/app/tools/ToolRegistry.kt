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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Android 工具注册表：把手机原生能力暴露给 LLM。
 * 每个工具含 JSON Schema 定义 + 执行器（通过无障碍服务/Shell 实现）。
 */
class ToolRegistry(private val context: Context) {

    private val plugins = com.dshnative.app.plugins.PluginRegistry(context)
    private val json = Json { ignoreUnknownKeys = true }

    /** 工具定义（JSON Schema）——只暴露启用插件的工具（插件架构） */
    fun definitions(): List<ApiToolDef> =
        allDefinitions().filter { it.function.name in plugins.enabledTools() }

    private fun allDefinitions(): List<ApiToolDef> = listOf(
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
        ),
        toolDef(
            "android_file_list",
            "列出目录内容。参数：path（可选，默认应用私有目录；也可用绝对路径如 /sdcard/Download）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "目录绝对路径，省略则用应用私有目录") })
                    }
                )
            )
        ),
        toolDef(
            "android_file_read",
            "读取文件内容（UTF-8 文本，最多 20000 字符）。参数：path（文件绝对路径）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "文件绝对路径") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("path")))
                )
            )
        ),
        toolDef(
            "android_file_write",
            "写入文本文件（UTF-8，覆盖写入）。参数：path（文件绝对路径），content（内容）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "文件绝对路径") })
                        put("content", buildJsonObject { put("type", "string"); put("description", "要写入的内容") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content")))
                )
            )
        ),
        toolDef(
            "android_clipboard_get",
            "读取系统剪贴板的当前文本内容。"
        ),
        toolDef(
            "android_clipboard_set",
            "设置系统剪贴板文本。参数：text（要复制的文本）。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("text", buildJsonObject { put("type", "string"); put("description", "要复制到剪贴板的文本") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("text")))
                )
            )
        ),
        toolDef(
            "android_web_fetch",
            "抓取指定 URL 的网页内容（返回纯文本，最多 8000 字符）。参数：url。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("url", buildJsonObject { put("type", "string"); put("description", "要抓取的网页 URL") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("url")))
                )
            )
        ),
        toolDef(
            "android_browser_open",
            "在系统浏览器中打开 URL。参数：url。",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to buildJsonObject {
                        put("url", buildJsonObject { put("type", "string"); put("description", "要打开的 URL") })
                    },
                    "required" to JsonArray(listOf(JsonPrimitive("url")))
                )
            )
        ),
        toolDef(
            "android_system_info",
            "获取设备信息：品牌型号、Android 版本、SDK、电池电量、网络类型等。"
        ),
        toolDef(
            "android_terminal_exec",
            "在手机内置的完整 Termux 终端环境（bash + coreutils + proot，无需 root）中执行任意 shell 命令。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "要执行的 shell 命令")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("command"))))
            }
        ),
        toolDef(
            "todo_write",
            "记录并更新任务列表（整个列表替换，last-write-wins）：每次调用传入完整的任务列表，覆盖之前的列表。参数：todos 数组，每项 {content(任务内容), status(pending/in_progress/completed)}。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("todos", buildJsonObject {
                        put("type", "array")
                        put("description", "完整任务列表")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("content", buildJsonObject { put("type", "string"); put("description", "任务内容") })
                                put("status", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("pending"), JsonPrimitive("in_progress"), JsonPrimitive("completed")))) })
                            })
                            put("required", JsonArray(listOf(JsonPrimitive("content"))))
                        })
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("todos"))))
            }
        ),
        toolDef(
            "jobs_run",
            "在后台执行一个 shell 命令（Termux 环境），立即返回 job_id。之后用 jobs_result 查询输出。参数：command。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "要在后台执行的 shell 命令")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("command"))))
            }
        ),
        toolDef(
            "jobs_list",
            "列出所有后台任务的 job_id、命令前缀与状态（running/finished）。"
        ),
        toolDef(
            "jobs_result",
            "查询后台任务输出。参数：job_id。任务完成后返回 exit/stdout/stderr；未完成返回 status=running。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("job_id", buildJsonObject { put("type", "string"); put("description", "jobs_run 返回的任务 id") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("job_id"))))
            }
        ),
        toolDef(
            "android_permission_list",
            "列出手机全部权限及其授予状态（授予/未授予）。可传 group 过滤（系统/存储/相机与麦克风/联系人与日历/电话与短信/网络与位置）。用于检查 AI 是否有能力执行某类操作。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("group", buildJsonObject { put("type", "string"); put("description", "可选：按分组过滤，如 系统/存储/相机与麦克风/联系人与日历/电话与短信/网络与位置") })
                })
            }
        ),
        toolDef(
            "android_permission_open_settings",
            "打开本应用的系统权限设置页，方便用户手动授权任意权限（相机/麦克风/联系人/存储/通知等）。"
        ),
        toolDef(
            "android_permission_enable_special",
            "打开特殊权限设置页。参数：key，可选 all_files（所有文件访问）/overlay（悬浮窗）/write_settings（修改系统设置）/exact_alarm（闹钟）/usage_stats（使用情况访问）。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("key", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf(JsonPrimitive("all_files"), JsonPrimitive("overlay"), JsonPrimitive("write_settings"), JsonPrimitive("exact_alarm"), JsonPrimitive("usage_stats"))))
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("key"))))
            }
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
        if (name !in plugins.enabledTools()) {
            return """{"error":"工具 '$name' 所在插件未启用，请先在插件设置中启用"}"""
        }
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
            "android_file_list" -> fileList(args)
            "android_file_read" -> fileRead(args)
            "android_file_write" -> fileWrite(args)
            "android_clipboard_get" -> clipboardGet()
            "android_clipboard_set" -> clipboardSet(args)
            "android_web_fetch" -> webFetch(args)
            "android_browser_open" -> browserOpen(args)
            "android_system_info" -> systemInfo()
            "android_terminal_exec" -> terminalExec(args)
            "todo_write" -> todoWrite(args)
            "jobs_run" -> jobsRun(args)
            "jobs_list" -> jobsList()
            "jobs_result" -> jobsResult(args)
            "android_permission_list" -> permissionList(args)
            "android_permission_open_settings" -> permissionOpenSettings()
            "android_permission_enable_special" -> permissionEnableSpecial(args)
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
            // 优先 su（root），无 root 时用系统 sh（受限）
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
            """{"error":"shell 执行失败: ${e.message}"}"""
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

    private fun fileList(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: context.filesDir.absolutePath
        return try {
            val dir = java.io.File(path)
            if (!dir.isDirectory) return """{"error":"不是目录: $path"}"""
            val items = dir.listFiles()?.sortedBy { it.name }?.take(200)?.map {
                """{"name":"${it.name}","type":${if (it.isDirectory) "\"dir\"" else "\"file\""},"size":${it.length()}}"""
            } ?: emptyList()
            """{"path":"$path","count":${items.size},"items":[${items.joinToString(",")}]}"""
        } catch (e: Exception) {
            """{"error":"读取目录失败: ${e.message}"}"""
        }
    }

    private fun fileRead(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 path"}"""
        return try {
            val text = java.io.File(path).readText()
            """{"path":"$path","content":${JsonPrimitive(text.take(20000))}}"""
        } catch (e: Exception) {
            """{"error":"读取文件失败: ${e.message}"}"""
        }
    }

    private fun fileWrite(args: JsonObject): String {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 path"}"""
        val content = args["content"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 content"}"""
        return try {
            val f = java.io.File(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            """{"ok":true,"path":"$path","bytes":${content.toByteArray().size}}"""
        } catch (e: Exception) {
            """{"error":"写入文件失败: ${e.message}（可能无权限，可尝试 android_shell_exec 用 su 执行）"}"""
        }
    }

    private fun clipboardGet(): String {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        return """{"clipboard":${JsonPrimitive(text.take(5000))}}"""
    }

    private fun clipboardSet(args: JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 text"}"""
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("dshnative", text))
        return """{"ok":true,"clipboard_set":${JsonPrimitive(text.take(200))}}"""
    }

    private fun webFetch(args: JsonObject): String {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 url"}"""
        return try {
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android)").build()
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) """{"error":"HTTP ${resp.code}"}"""
                    else """{"url":"$url","content":${JsonPrimitive(resp.body?.string()?.take(8000) ?: "")}}"""
                }
        } catch (e: Exception) {
            """{"error":"抓取失败: ${e.message}"}"""
        }
    }

    private fun browserOpen(args: JsonObject): String {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 url"}"""
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            """{"ok":true,"opened":"$url"}"""
        } catch (e: Exception) {
            """{"error":"打开浏览器失败: ${e.message}"}"""
        }
    }

    private fun systemInfo(): String {
        val battery = IntentBattery()
        val info = buildJsonObject {
            put("brand", android.os.Build.BRAND)
            put("model", android.os.Build.MODEL)
            put("device", android.os.Build.DEVICE)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("battery_pct", battery)
            put("network", networkType())
        }
        return """{"device":$info}"""
    }

    /** 内置 Termux 终端执行 */
    private fun terminalExec(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 command"}"""
        com.dshnative.app.termux.TermuxManager.init(context)
        return com.dshnative.app.termux.TermuxManager.exec(cmd)
    }

    // ---------- tool-todo（todo_write，整个列表替换，last-write-wins） ----------

    private val todoPrefs by lazy { context.getSharedPreferences("dshnative_todos", Context.MODE_PRIVATE) }

    private fun todoWrite(args: JsonObject): String {
        val raw = args["todos"] ?: return """{"error":"缺少 todos 参数"}"""
        return try {
            val list = raw.jsonArray.map { item ->
                val obj = item.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                if (content.isEmpty()) throw IllegalArgumentException("todo content 不能为空")
                """{"content":${JsonPrimitive(content)},"status":${JsonPrimitive(status)}}"""
            }
            todoPrefs.edit().putString("todos", "[${list.joinToString(",")}]").apply()
            """{"ok":true,"todos":[${list.joinToString(",")}],"count":${list.size}}"""
        } catch (e: Exception) {
            """{"error":"todo 写入失败: ${e.message}"}"""
        }
    }

    // ---------- tool-jobs（后台任务） ----------

    private data class JobState(
        val command: String,
        var status: String = "running", // running / finished
        var exit: Int = -1,
        var stdout: String = "",
        var stderr: String = ""
    )

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, JobState>()

    private fun jobsRun(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 command"}"""
        val id = java.util.UUID.randomUUID().toString().take(8)
        jobs[id] = JobState(command = cmd)
        com.dshnative.app.termux.TermuxManager.init(context)
        Thread {
            try {
                val result = com.dshnative.app.termux.TermuxManager.exec(cmd)
                val parsed = try {
                    kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
                } catch (_: Exception) { null }
                val state = jobs[id]
                if (state != null) {
                    state.exit = parsed?.get("exit")?.jsonPrimitive?.intOrNull ?: -1
                    state.stdout = parsed?.get("stdout")?.jsonPrimitive?.contentOrNull ?: result
                    state.stderr = parsed?.get("stderr")?.jsonPrimitive?.contentOrNull ?: ""
                    state.status = "finished"
                }
            } catch (e: Exception) {
                val state = jobs[id]
                if (state != null) { state.stderr = e.message ?: "error"; state.status = "finished" }
            }
        }.start()
        return """{"ok":true,"job_id":"$id","command":${JsonPrimitive(cmd.take(120))}}"""
    }

    private fun jobsList(): String {
        val items = jobs.map { (id, s) ->
            """{"job_id":"$id","status":${JsonPrimitive(s.status)},"command":${JsonPrimitive(s.command.take(100))}}"""
        }
        return """{"count":${items.size},"jobs":[${items.joinToString(",")}]}"""
    }

    private fun jobsResult(args: JsonObject): String {
        val id = args["job_id"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"缺少 job_id"}"""
        val s = jobs[id] ?: return """{"error":"未知 job_id: $id"}"""
        if (s.status == "running") return """{"job_id":"$id","status":"running"}"""
        return """{"job_id":"$id","status":"finished","exit":${s.exit},"stdout":${JsonPrimitive(s.stdout.take(8000))},"stderr":${JsonPrimitive(s.stderr.take(2000))}}"""
    }

    private fun IntentBattery(): Int {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun networkType(): String {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val info = cm.activeNetworkInfo ?: return "none"
        return when (info.type) {
            android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
            android.net.ConnectivityManager.TYPE_MOBILE -> "mobile"
            else -> info.typeName ?: "unknown"
        }
    }

    // ---------- 权限插件（permission tools：AI 可列出/引导授予各项权限） ----------

    private fun permissionList(args: JsonObject): String {
        val groupFilter = args["group"]?.jsonPrimitive?.contentOrNull
        val pm = com.dshnative.app.permissions.PermissionManager
        val items = pm.all.filter { groupFilter.isNullOrBlank() || it.group == groupFilter }
        val arr = buildJsonArray {
            items.forEach { item ->
                add(buildJsonObject {
                    put("name", item.name)
                    put("group", item.group)
                    put("granted", pm.isGranted(context, item))
                    put("permission", item.permission ?: "special:${item.specialKey}")
                })
            }
        }
        val grantedCount = arr.count {
            it.jsonObject["granted"]?.jsonPrimitive?.contentOrNull == "true"
        }
        return """{"count":${items.size},"granted_count":$grantedCount,"permissions":$arr}"""
    }

    private fun permissionOpenSettings(): String {
        return try {
            val intent = com.dshnative.app.permissions.PermissionManager.buildAppSettingsIntent(context)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            """{"ok":true,"opened":"应用权限设置页"}"""
        } catch (e: Exception) {
            """{"error":"打开权限设置失败: ${e.message}"}"""
        }
    }

    private fun permissionEnableSpecial(args: JsonObject): String {
        val key = args["key"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"缺少 key（all_files/overlay/write_settings/exact_alarm/usage_stats）"}"""
        return try {
            val intent = com.dshnative.app.permissions.PermissionManager.buildSpecialSettingsIntent(context, key)
                ?: return """{"error":"无效的特殊权限 key: $key"}"""
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            """{"ok":true,"opened":"特殊权限设置页:$key"}"""
        } catch (e: Exception) {
            """{"error":"打开特殊权限设置失败: ${e.message}"}"""
        }
    }
}