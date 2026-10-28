package com.dshnative.app.plugins

import android.content.Context

/**
 * 插件注册表（对应 DSH dsh-plugin-manager / dsh-plugin-inventory）。
 * 管理内置插件列表与启用状态；只有启用插件提供的工具才会暴露给 Agent。
 */
class PluginRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("dshnative_plugins", Context.MODE_PRIVATE)

    /** 内置插件清单（对应 DSH 官方内置插件） */
    val builtin: List<DshPlugin> = listOf(
        DshPlugin(
            id = "accessibility",
            name = "屏幕操控",
            description = "无障碍服务：读取屏幕 UI 树、截图、点击、输入文本、滑动、全局动作（Home/Back/最近任务）。",
            tools = listOf("android_ui_tree", "android_screenshot", "android_ui_click", "android_ui_input", "android_ui_swipe", "android_ui_global"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "system",
            name = "系统",
            description = "执行 shell 命令、列出应用、读取设备信息（型号/电量/网络）。",
            tools = listOf("android_shell_exec", "android_app_list", "android_system_info"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "filesystem",
            name = "文件系统",
            description = "浏览目录、读取/写入文件（应用私有目录与公共目录）。",
            tools = listOf("android_file_list", "android_file_read", "android_file_write"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "clipboard",
            name = "剪贴板",
            description = "读取与设置系统剪贴板文本。",
            tools = listOf("android_clipboard_get", "android_clipboard_set"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "web",
            name = "网页",
            description = "抓取网页内容、在浏览器中打开 URL。",
            tools = listOf("android_web_fetch", "android_browser_open"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "terminal",
            name = "终端",
            description = "内置 Termux 终端环境（bash + coreutils + proot），可执行任意命令，无需 root。",
            tools = listOf("android_terminal_exec"),
            defaultEnabled = true
        ),
        DshPlugin(
            id = "shizuku",
            name = "Shizuku 免 Root",
            description = "通过 Shizuku 获得更完整的系统权限（需安装 Shizuku 应用并授权）。",
            tools = emptyList(),
            defaultEnabled = false,
            builtin = false
        )
    )

    fun all(): List<DshPlugin> = builtin.map { it.copy().apply { enabled = isEnabled(it.id) } }

    fun isEnabled(id: String): Boolean {
        val p = builtin.firstOrNull { it.id == id } ?: return false
        return prefs.getBoolean("enabled_$id", p.defaultEnabled)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    /** 返回当前启用插件暴露的所有工具名 */
    fun enabledTools(): Set<String> =
        builtin.filter { isEnabled(it.id) }.flatMap { it.tools }.toSet()
}