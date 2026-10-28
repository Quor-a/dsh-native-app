package com.dshnative.app.plugins

import android.content.Context
import org.json.JSONObject

/**
 * 插件注册表（对齐 DSH dsh-plugin-manager / dsh-plugin-inventory / dsh-plugin-market）。
 *
 * 每个插件 = bundle 元数据 + 配置项 + 工具集 + API 端点集 + 系统提示词片段。
 * - [setEnabled] / [setBundleEnabled]：对应官方 bundle 的启用/停用；
 * - [removeBundle]：对应官方卸载（本地可选插件卸载 = 停用并从启用列表移除）；
 * - [config] / [saveConfig]：插件配置面板的动态表单持久化；
 * - [enabledPrompts]：拼接启用插件的系统提示词片段，供 AgentEngine 注入；
 * - [marketIndex]：插件市场索引（全部官方插件 + 本地可选插件）。
 */
class PluginRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("dshnative_plugins", Context.MODE_PRIVATE)
    private val configPrefs = context.getSharedPreferences("dshnative_plugin_configs", Context.MODE_PRIVATE)

    /** 插件服务实例表（对应 cordis Service，供插件间依赖注入） */
    private val services = mutableMapOf<String, Any>()

    /** 内置插件清单（对齐 DSH 官方插件体系：工具 / 服务 / UI / 系统） */
    val builtin: List<DshPlugin> = listOf(
        // ---------- 系统核心（不可停用） ----------
        DshPlugin(
            id = "session",
            name = "会话",
            description = "会话管理与持久化（对应 dsh-session）：多会话、消息历史、事件日志。",
            category = PluginCategory.SYSTEM,
            api = listOf("session.list", "session.create", "session.delete", "session.message"),
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-api-session-controller"
        ),
        DshPlugin(
            id = "agent",
            name = "智能体",
            description = "Agent 循环与工具编排（对应 dsh-agent / dsh-agent-loop）：流式对话、工具调用、最多 200 轮安全上限。",
            category = PluginCategory.SYSTEM,
            dependencies = listOf("session", "tools"),
            api = listOf("agent.chat", "agent.tools"),
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-settings-agent-loop"
        ),

        // ---------- 工具插件（面向模型） ----------
        DshPlugin(
            id = "tool-bash",
            name = "Bash 命令",
            description = "在手机内置 Termux（bash + coreutils + proot，免 root）中执行任意 shell 命令（对应 dsh-tool-bash）。",
            category = PluginCategory.TOOL,
            dependencies = listOf("terminal"),
            tools = listOf("android_terminal_exec"),
            configItems = listOf(
                PluginConfigItem("shell", PluginConfigType.ENUM, "Shell", "命令使用的 shell", "sh", listOf("sh", "bash")),
                PluginConfigItem("timeout", PluginConfigType.TEXT, "超时(秒)", "单条命令执行超时", "30")
            ),
            prompt = "你可以通过内置 Termux 终端（bash）执行 shell 命令来完成文件操作、安装软件、运行脚本等任务。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-api-terminal-controller"
        ),
        DshPlugin(
            id = "tool-fs",
            name = "文件系统",
            description = "浏览目录、读取/写入文件（应用私有目录与公共目录，对应 dsh-tool-fs）。",
            category = PluginCategory.TOOL,
            dependencies = listOf("workspace"),
            tools = listOf("android_file_list", "android_file_read", "android_file_write"),
            prompt = "你可以浏览应用内文件系统、读取和写入文本文件（在应用私有目录与公开下载目录中）。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-api-workspace-files"
        ),
        DshPlugin(
            id = "tool-web",
            name = "网页",
            description = "抓取网页内容、在浏览器中打开 URL（对应 dsh-tool-web）。",
            category = PluginCategory.TOOL,
            tools = listOf("android_web_fetch", "android_browser_open"),
            configItems = listOf(
                PluginConfigItem("maxChars", PluginConfigType.TEXT, "最大字符数", "抓取正文最大长度", "6000")
            ),
            prompt = "你可以抓取网页内容（转换为纯文本）或在系统浏览器中打开 URL。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-tool-web-search"
        ),
        DshPlugin(
            id = "tool-todo",
            name = "任务列表",
            description = "记录并更新任务列表（整个列表替换，last-write-wins，对应 dsh-tool-todo 的 todo_write）。",
            category = PluginCategory.TOOL,
            tools = listOf("todo_write"),
            prompt = "你可以维护一个任务列表：添加、更新、标记完成。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-tool-todo"
        ),
        DshPlugin(
            id = "tool-jobs",
            name = "后台任务",
            description = "在后台执行 shell 命令并轮询结果（对应 dsh-tool-jobs）：jobs_run / jobs_list / jobs_result。",
            category = PluginCategory.TOOL,
            dependencies = listOf("tool-bash"),
            tools = listOf("jobs_run", "jobs_list", "jobs_result"),
            prompt = "你可以启动后台任务（长时间运行的命令）并轮询其结果。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-api-job-controller"
        ),
        DshPlugin(
            id = "accessibility",
            name = "屏幕操控",
            description = "无障碍服务：读取屏幕 UI 树、截图、点击、输入文本、滑动、全局动作。",
            category = PluginCategory.TOOL,
            tools = listOf("android_ui_tree", "android_screenshot", "android_ui_click", "android_ui_input", "android_ui_swipe", "android_ui_global"),
            prompt = "你可以通过无障碍服务读取屏幕 UI 树、截图、点击、输入文本、滑动屏幕和执行全局动作。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-input-trigger"
        ),
        DshPlugin(
            id = "clipboard",
            name = "剪贴板",
            description = "读取与设置系统剪贴板文本。",
            category = PluginCategory.TOOL,
            tools = listOf("android_clipboard_get", "android_clipboard_set"),
            prompt = "你可以读取或设置系统剪贴板文本。",
            defaultEnabled = true
        ),
        DshPlugin(
            id = "system",
            name = "系统",
            description = "执行 shell 命令（su）、列出应用、读取设备信息。",
            category = PluginCategory.TOOL,
            tools = listOf("android_shell_exec", "android_app_list", "android_system_info"),
            prompt = "你可以执行 shell 命令、列出已安装应用、读取设备信息。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-api-gateway"
        ),
        DshPlugin(
            id = "permissions",
            name = "权限管理",
            description = "列出/检查手机全部权限（通知/相机/麦克风/联系人/日历/电话/短信/存储/悬浮窗/所有文件访问等），引导用户授权。",
            category = PluginCategory.TOOL,
            tools = listOf(
                "android_permission_list",
                "android_permission_open_settings",
                "android_permission_enable_special"
            ),
            prompt = "你可以列出设备权限、检查授权状态并引导用户到系统设置页开启权限。",
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-permission-presets"
        ),

        // ---------- 功能服务插件 ----------
        DshPlugin(
            id = "workspace",
            name = "工作区",
            description = "工作区与文件管理（对应 dsh-workspace / dsh-workspace-files）：文件浏览、文本编辑、新建。",
            category = PluginCategory.SERVICE,
            api = listOf("workspace.list", "workspace.read", "workspace.write", "workspace.create"),
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-workspace"
        ),
        DshPlugin(
            id = "terminal",
            name = "终端",
            description = "内置 Termux 终端环境（bash + coreutils + proot，免 root）。",
            category = PluginCategory.SERVICE,
            tools = listOf("android_terminal_exec"),
            api = listOf("terminal.exec"),
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-api-terminal-controller"
        ),
        DshPlugin(
            id = "settings",
            name = "设置",
            description = "应用设置与模型配置（对应 dsh-settings / dsh-settings-models）。",
            category = PluginCategory.SERVICE,
            api = listOf("settings.get", "settings.models"),
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-settings-models"
        ),
        DshPlugin(
            id = "plugin-manager",
            name = "插件管理",
            description = "插件清单与启停管理（对应 dsh-plugin-manager）。",
            category = PluginCategory.SERVICE,
            api = listOf("plugins.list", "plugins.setEnabled"),
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-plugin-manager"
        ),

        // ---------- UI 插件（原生界面页） ----------
        DshPlugin(
            id = "chat",
            name = "对话界面",
            description = "主聊天界面（对应 dsh-client-ui-chat）。",
            category = PluginCategory.UI,
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-chat"
        ),
        DshPlugin(
            id = "sidebar",
            name = "侧栏",
            description = "会话侧栏与导航（对应 dsh-client-ui-sidebar）。",
            category = PluginCategory.UI,
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-sidebar"
        ),
        DshPlugin(
            id = "model-config",
            name = "模型配置",
            description = "模型配置界面（对应 dsh-client-ui-settings-models，移植自 ZorvAI）。",
            category = PluginCategory.UI,
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-settings-models"
        ),
        DshPlugin(
            id = "plugin-market",
            name = "插件市场",
            description = "插件管理与安装界面（对应 dsh-client-ui-plugin-inventory）。",
            category = PluginCategory.UI,
            defaultEnabled = true,
            officialId = "@deepseek-ai/dsh-client-ui-plugin-inventory"
        ),

        // ---------- 可选插件（对应 plugin-market 可安装） ----------
        DshPlugin(
            id = "shizuku",
            name = "Shizuku 免 Root",
            description = "通过 Shizuku 获得更完整的系统权限（需安装 Shizuku 并授权）：检测授权、发起授权、以 Shizuku 权限执行 shell 命令。",
            category = PluginCategory.TOOL,
            tools = listOf("android_shizuku_status", "android_shizuku_request", "android_shizuku_exec"),
            configItems = listOf(
                PluginConfigItem("mode", PluginConfigType.ENUM, "执行模式", "命令执行方式", "shell", listOf("shell", "split"))
            ),
            prompt = "你可以通过 Shizuku 以 adb 级权限执行 shell 命令（需 Shizuku 已授权）。",
            defaultEnabled = true,
            builtin = false,
            optional = true,
            officialId = "@deepseek-ai/dsh-api-terminal-controller"
        ),
        DshPlugin(
            id = "root",
            name = "Root 权限",
            description = "检测 Root 可用性（su）并以 root 权限执行 shell 命令（需设备已 root）。",
            category = PluginCategory.TOOL,
            tools = listOf("android_root_status", "android_root_exec"),
            prompt = "你可以通过 su 以 root 权限执行 shell 命令（需设备已 root）。",
            defaultEnabled = true,
            builtin = false,
            optional = true,
            officialId = "@deepseek-ai/dsh-api-terminal-controller"
        )
    )

    /** 全部插件（带启用状态） */
    fun all(): List<DshPlugin> = builtin.map { it.copy().apply { enabled = isEnabled(it.id) } }

    fun byId(id: String): DshPlugin? = builtin.firstOrNull { it.id == id }

    fun byBundle(bundle: String): DshPlugin? =
        builtin.firstOrNull { it.bundleName == bundle || it.officialId == bundle }

    fun isEnabled(id: String): Boolean {
        val p = builtin.firstOrNull { it.id == id } ?: return false
        return prefs.getBoolean("enabled_$id", p.defaultEnabled)
    }

    /** 按插件 id 设置启用状态 */
    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    /** 按官方 bundle 名设置启用状态（对应 dsh-plugin-manager.setBundleEnabled） */
    fun setBundleEnabled(bundle: String, enabled: Boolean) {
        byBundle(bundle)?.let { setEnabled(it.id, enabled) }
    }

    /** 卸载插件（对应 dsh-plugin-manager.removeBundle）：可选插件卸载 = 停用 */
    fun removeBundle(bundle: String) {
        byBundle(bundle)?.let { if (it.optional) setEnabled(it.id, false) }
    }

    /** 读取插件配置（缺省用默认值） */
    fun config(id: String): Map<String, String> {
        val p = byId(id) ?: return emptyMap()
        val stored = configPrefs.getString("config_$id", null) ?: return p.configItems.associate { it.key to it.defaultValue }
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: JSONObject()
        return p.configItems.associate { item ->
            item.key to json.optString(item.key, item.defaultValue)
        }
    }

    /** 保存插件配置（动态表单提交） */
    fun saveConfig(id: String, values: Map<String, String>) {
        byId(id) ?: return
        val merged = config(id).toMutableMap().apply { putAll(values) }
        configPrefs.edit().putString("config_$id", JSONObject(merged).toString()).apply()
    }

    /** 拼接所有启用插件注入系统提示词的片段（供 AgentEngine 使用） */
    fun enabledPrompts(): String =
        builtin.filter { isEnabled(it.id) }
            .map { it.prompt.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

    /** 插件目录（给插件管理 UI / 协议层）：含配置项与启用状态 */
    fun pluginCatalog(): List<DshPlugin> = all()

    /** 插件市场索引（已安装 + 可安装的官方插件全集） */
    fun marketIndex(): List<DshPlugin> = all()

    /** 返回当前启用插件暴露的所有工具名（供 ToolRegistry 过滤） */
    fun enabledTools(): Set<String> =
        builtin.filter { isEnabled(it.id) }.flatMap { it.tools }.toSet()

    /** 返回当前启用插件注册的所有 API 端点名（供前端协议层使用） */
    fun enabledApi(): Set<String> =
        builtin.filter { isEnabled(it.id) }.flatMap { it.api }.toSet()

    /** 注册插件服务实例（对应 cordis ctx.provide） */
    fun registerService(id: String, service: Any) {
        services[id] = service
    }

    /** 获取插件服务实例（对应 cordis ctx.inject） */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(id: String): T? = services[id] as? T
}