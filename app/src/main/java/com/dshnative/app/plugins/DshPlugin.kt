package com.dshnative.app.plugins

/**
 * 插件分类（对应 DSH 插件体系：工具插件 / 服务插件 / 前端 UI 插件 / 系统核心）。
 */
enum class PluginCategory(val label: String) {
    TOOL("工具"),
    SERVICE("功能"),
    UI("界面"),
    SYSTEM("系统")
}

/**
 * DSH 插件模型（安卓原生版，对应 DSH 的 cordis 插件 + defineTool 体系）。
 *
 * 每个插件 = 元数据 + 依赖注入 + 工具集 + API 端点集。
 * - 工具插件（如 tool-bash / tool-web）面向模型，提供可调用的工具；
 * - 服务插件（如 session / workspace / settings）面向系统功能，为其他插件提供 API；
 * - UI 插件（如 chat / terminal / plugin-market）对应原生界面页；
 * - 系统插件（如 agent / session）为核心能力，通常不可停用。
 */
data class DshPlugin(
    val id: String,
    val name: String,
    val description: String,
    val category: PluginCategory = PluginCategory.TOOL,
    val version: String = "0.2.1",
    val author: String = "DshNative",
    /** 依赖的其他插件服务（对应 cordis inject） */
    val dependencies: List<String> = emptyList(),
    /** 该插件提供给模型的所有工具名（对应 defineTool 注册的工具） */
    val tools: List<String> = emptyList(),
    /** 该插件提供给前端的 API 端点名（对应 dsh.api.* 网关，供协议层使用） */
    val api: List<String> = emptyList(),
    /** 默认是否启用 */
    val defaultEnabled: Boolean = true,
    /** 是否内置（不可卸载） */
    val builtin: Boolean = true,
    /** 是否可选安装（对应 dsh-plugin-market 的可安装插件） */
    val optional: Boolean = false
) {
    var enabled: Boolean = defaultEnabled
        get() = field
        set(value) {
            field = value
        }
}