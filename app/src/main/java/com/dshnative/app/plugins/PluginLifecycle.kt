package com.dshnative.app.plugins

import org.json.JSONObject

/**
 * 插件运行时上下文（对齐 DSH cordis 插件的 `ctx` 对象）。
 *
 * 插件在 [PluginLifecycle.onStart] 时获得该上下文，可：
 * - 读取/写入自己的配置（对齐 `ctx.config` 与 `ctx.configForms.get`）
 * - 获取其他插件提供的服务（对齐 `ctx.inject` / `ctx.get`）
 * - 注册服务供其他插件注入（对齐 `ctx.provide`）
 * - 订阅 / 发布事件（对齐 `ctx.on` / `ctx.emit`）
 * - 注册工具与 API 端点（对齐插件 defineTool / dsh.api 网关）
 */
class PluginContext(
    private val registry: PluginRegistry,
    val pluginId: String
) {
    /** 读取插件配置（缺省用默认值） */
    fun config(): Map<String, String> = registry.config(pluginId)

    /** 获取其他插件提供的服务（对应 cordis ctx.get） */
    fun <T : Any> getService(id: String): T? = registry.service(id)

    /** 注册一个服务供其他插件注入（对应 cordis ctx.provide） */
    fun provideService(id: String, service: Any) {
        registry.registerService(id, service)
    }

    /** 订阅事件（对应 cordis ctx.on） */
    fun on(event: String, handler: (JSONObject) -> Unit) {
        registry.on(event, pluginId, handler)
    }

    /** 发布事件（对应 cordis ctx.emit） */
    fun emit(event: String, payload: JSONObject) {
        registry.emit(event, payload)
    }
}

/**
 * 插件生命周期接口（对齐 cordis 插件的 apply(ctx) / start / stop 钩子）。
 *
 * 启用插件时调用 [onStart]（注册服务/事件/工具/API）；
 * 停用插件时调用 [onStop]（清理资源）。默认实现为空。
 */
interface PluginLifecycle {
    fun onStart(ctx: PluginContext) = Unit
    fun onStop(ctx: PluginContext) = Unit
}