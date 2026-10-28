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

/** 插件配置项类型（对应 DSH 插件 config schema 的字段类型） */
enum class PluginConfigType(val label: String) {
    TEXT("文本"),
    BOOLEAN("开关"),
    ENUM("枚举")
}

/**
 * 单个插件配置项（对应 DSH 插件 config schema 的字段）。
 * 动态渲染成表单：文本输入 / 开关 / 枚举单选。
 */
data class PluginConfigItem(
    val key: String,
    val type: PluginConfigType,
    val label: String,
    val description: String = "",
    val defaultValue: String = "",
    val options: List<String> = emptyList()
)

/**
 * DSH 插件模型（安卓原生版，对齐 DSH cordis 插件 + defineTool + 插件市场 bundle 体系）。
 *
 * 每个插件 = 元数据(bundle) + 依赖注入 + 配置项 + 工具集 + API 端点集 + 系统提示词片段。
 * - bundleName：对应官方插件市场中的包名（如 @deepseek-ai/dsh-client-ui-chat）；
 * - configItems：插件配置面板动态表单的字段；
 * - prompt：插件注入到 Agent 系统提示词的能力片段；
 * - tools：插件提供给模型的工具（defineTool 注册）；
 * - api：插件提供给前端协议层的 API 端点。
 */
data class DshPlugin(
    val id: String,
    val name: String,
    val description: String,
    val category: PluginCategory = PluginCategory.TOOL,
    val version: String = "0.2.1",
    val author: String = "DshNative",
    /** 官方 bundle 名（插件市场索引主键，如 @deepseek-ai/dsh-client-ui-chat） */
    val bundleName: String = "",
    /** 依赖的其他插件服务（对应 cordis inject） */
    val dependencies: List<String> = emptyList(),
    /** 该插件提供给模型的所有工具名（对应 defineTool 注册的工具） */
    val tools: List<String> = emptyList(),
    /** 该插件提供给前端的 API 端点名（对应 dsh.api.* 网关，供协议层使用） */
    val api: List<String> = emptyList(),
    /** 插件配置项（配置面板动态表单） */
    val configItems: List<PluginConfigItem> = emptyList(),
    /** 插件注入 Agent 系统提示词的能力片段 */
    val prompt: String = "",
    /** 默认是否启用 */
    val defaultEnabled: Boolean = true,
    /** 是否内置（不可卸载） */
    val builtin: Boolean = true,
    /** 是否可选安装（对应 dsh-plugin-market 的可安装插件） */
    val optional: Boolean = false,
    /** DSH 官方插件 id（如 @deepseek-ai/dsh-client-ui-chat），用于与官方插件体系对齐 */
    val officialId: String = ""
) {
    var enabled: Boolean = defaultEnabled
        get() = field
        set(value) {
            field = value
        }
}