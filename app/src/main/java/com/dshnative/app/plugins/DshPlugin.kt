package com.dshnative.app.plugins

import kotlinx.serialization.Serializable

/**
 * DSH 插件模型（安卓原生版）。
 * 插件 = 一组工具 + 元数据 + 启用状态。对应 DSH 的 dsh-plugin-manager / plugin-inventory。
 */
@Serializable
data class DshPlugin(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String = "DshNative",
    /** 该插件提供的工具名列表（对应 ToolRegistry 中的工具名） */
    val tools: List<String> = emptyList(),
    /** 默认是否启用 */
    val defaultEnabled: Boolean = true,
    /** 是否内置（不可卸载） */
    val builtin: Boolean = true
) {
    var enabled: Boolean = defaultEnabled
        get() = field
        set(value) {
            field = value
        }
}