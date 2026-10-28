package com.dshnative.app.plugins

import android.content.Context

/**
 * 插件注册表（对应 DSH dsh-plugin-manager / dsh-plugin-inventory）。
 *
 * 一切皆插件：管理所有内置插件（工具 / 功能 / 界面 / 系统）的清单与启用状态；
 * 只有启用插件提供的工具才会暴露给 Agent，启用插件提供的 API 才会注册给前端协议层。
 * 同时提供简单的服务注入（对应 cordis 的 inject 依赖）。
 */
class PluginRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("dshnative_plugins", Context.MODE_PRIVATE)

    /** 插件服务实例表（对应 cordis Service，供插件间依赖注入） */
    private val services = mutableMapOf<String, Any>()

    /** 内置插件清单（对应 DSH 官方插件：工具 / 服务 / UI / 系统） */
    val builtin: List<DshPlugin> = listOf(
        // ---------- 系统核心（不可停用） ----------
        DshPlugin(
            id = "session",
            name = "会话",
            description = "会话管理与持久化（对应 dsh-session）：多会话、消息历史、事件日志。",
            category = PluginCategory.SYSTEM,
            api = listOf("session.list", "session.create", "session.delete", "session.message"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "agent",
            name = "智能体",
            description = "Agent 循环与工具编排（对应 dsh-agent / dsh-agent-loop）：流式对话、工具调用、最多 200 轮安全上限。",
            category = PluginCategory.SYSTEM,
            dependencies = listOf("session", "tools"),
            api = listOf("agent.chat", "agent.tools"),
            defaultEnabled = true
        ),

        // ---------- 工具插件（面向模型） ----------
        DshPlugin(
            id = "tool-bash",
            name = "Bash 命令",
            description = "在手机内置 Termux（bash + coreutils + proot，免 root）中执行任意 shell 命令（对应 dsh-tool-bash）。",
            category = PluginCategory.TOOL,
            dependencies = listOf("terminal"),
            tools = listOf("android_terminal_exec"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "tool-fs",
            name = "文件系统",
            description = "浏览目录、读取/写入文件（应用私有目录与公共目录，对应 dsh-tool-fs）。",
            category = PluginCategory.TOOL,
            dependencies = listOf("workspace"),
            tools = listOf("android_file_list", "android_file_read", "android_file_write"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "tool-web",
            name = "网页",
            description = "抓取网页内容、在浏览器中打开 URL（对应 dsh-tool-web）。",
            category = PluginCategory.TOOL,
            tools = listOf("android_web_fetch", "android_browser_open"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "tool-todo",
            name = "任务列表",
            description = "记录并更新任务列表（整个列表替换，last-write-wins，对应 dsh-tool-todo 的 todo_write）。",
            category = PluginCategory.TOOL,
            tools = listOf("todo_write"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "tool-jobs",
            name = "后台任务",
            description = "在后台执行 shell 命令并轮询结果（对应 dsh-tool-jobs）：jobs_run / jobs_list / jobs_result。",
            category = PluginCategory.TOOL,
            dependencies = listOf("tool-bash"),
            tools = listOf("jobs_run", "jobs_list", "jobs_result"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "accessibility",
            name = "屏幕操控",
            description = "无障碍服务：读取屏幕 UI 树、截图、点击、输入文本、滑动、全局动作。",
            category = PluginCategory.TOOL,
            tools = listOf("android_ui_tree", "android_screenshot", "android_ui_click", "android_ui_input", "android_ui_swipe", "android_ui_global"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "clipboard",
            name = "剪贴板",
            description = "读取与设置系统剪贴板文本。",
            category = PluginCategory.TOOL,
            tools = listOf("android_clipboard_get", "android_clipboard_set"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "system",
            name = "系统",
            description = "执行 shell 命令（su）、列出应用、读取设备信息。",
            category = PluginCategory.TOOL,
            tools = listOf("android_shell_exec", "android_app_list", "android_system_info"),
            defaultEnabled = true
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
            defaultEnabled = true
        ),

        // ---------- 功能服务插件 ----------
        DshPlugin(
            id = "workspace",
            name = "工作区",
            description = "工作区与文件管理（对应 dsh-workspace / dsh-workspace-files）：文件浏览、文本编辑、新建。",
            category = PluginCategory.SERVICE,
            api = listOf("workspace.list", "workspace.read", "workspace.write", "workspace.create"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "terminal",
            name = "终端",
            description = "内置 Termux 终端环境（bash + coreutils + proot，免 root）。",
            category = PluginCategory.SERVICE,
            tools = listOf("android_terminal_exec"),
            api = listOf("terminal.exec"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "settings",
            name = "设置",
            description = "应用设置与模型配置（对应 dsh-settings / dsh-settings-models）。",
            category = PluginCategory.SERVICE,
            api = listOf("settings.get", "settings.models"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "plugin-manager",
            name = "插件管理",
            description = "插件清单与启停管理（对应 dsh-plugin-manager）。",
            category = PluginCategory.SERVICE,
            api = listOf("plugins.list", "plugins.setEnabled"),
            defaultEnabled = true
        ),

        // ---------- UI 插件（原生界面页） ----------
        DshPlugin(
            id = "chat",
            name = "对话界面",
            description = "主聊天界面（对应 dsh-client-ui-chat）。",
            category = PluginCategory.UI,
            defaultEnabled = true
        ),
        DshPlugin(
            id = "sidebar",
            name = "侧栏",
            description = "会话侧栏与导航（对应 dsh-client-ui-sidebar）。",
            category = PluginCategory.UI,
            defaultEnabled = true
        ),
        DshPlugin(
            id = "model-config",
            name = "模型配置",
            description = "模型配置界面（对应 dsh-client-ui-settings-models，移植自 ZorvAI）。",
            category = PluginCategory.UI,
            defaultEnabled = true
        ),
        DshPlugin(
            id = "plugin-market",
            name = "插件市场",
            description = "插件管理与安装界面（对应 dsh-client-ui-plugin-market）。",
            category = PluginCategory.UI,
            defaultEnabled = true
        ),

        // ---------- 可选插件（对应 plugin-market 可安装） ----------
        DshPlugin(
            id = "shizuku",
            name = "Shizuku 免 Root",
            description = "通过 Shizuku 获得更完整的系统权限（需安装 Shizuku 并授权）。",
            category = PluginCategory.TOOL,
            tools = emptyList(),
            defaultEnabled = false,
            builtin = false,
            optional = true
        )
    )

    /** 全部插件（带启用状态） */
    fun all(): List<DshPlugin> = builtin.map { it.copy().apply { enabled = isEnabled(it.id) } }

    fun byId(id: String): DshPlugin? = builtin.firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean {
        val p = builtin.firstOrNull { it.id == id } ?: return false
        return prefs.getBoolean("enabled_$id", p.defaultEnabled)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

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