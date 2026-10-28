package com.dshnative.app.plugins

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * 插件注册表（对齐 DSH dsh-plugin-manager / dsh-plugin-inventory / dsh-plugin-market）。
 *
 * 每个插件 = bundle 元数据 + 配置项 + 工具集 + API 端点集 + 系统提示词片段。
 * - [setEnabled] / [setBundleEnabled]：对应官方 bundle 的启用/停用；
 * - [removeBundle]：对应官方卸载（本地可选插件卸载 = 停用并从启用列表移除）；
 * - [config] / [saveConfig]：插件配置面板的动态表单持久化；
 * - [enabledPrompts]：拼接启用插件的系统提示词片段，供 AgentEngine 注入；
 * - [marketIndex]：插件市场索引（官方 72 个 @deepseek-ai/dsh-* 插件 + 本地可选插件）。
 */
class PluginRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("dshnative_plugins", Context.MODE_PRIVATE)
    private val configPrefs = context.getSharedPreferences("dshnative_plugin_configs", Context.MODE_PRIVATE)

    /** 插件服务实例表（对应 cordis Service，供插件间依赖注入） */
    private val services = mutableMapOf<String, Any>()

    /** 插件生命周期实现（对应 cordis 插件的 apply(ctx) / start / stop 钩子） */
    private val lifecycles = mutableMapOf<String, PluginLifecycle>()

    /** 事件总线：event -> (pluginId, handler)（对应 cordis ctx.on / ctx.emit） */
    private val events = mutableMapOf<String, MutableList<Pair<String, (JSONObject) -> Unit>>>()

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

    /** 把任意插件 id（native id 或官方 bundle 名）解析为本地持久化主键。 */
    private fun resolveNativeId(id: String): String =
        if (builtin.any { it.id == id }) id
        else builtin.firstOrNull { it.officialId == id }?.id ?: id

    fun isEnabled(id: String): Boolean {
        val nativeId = resolveNativeId(id)
        val p = builtin.firstOrNull { it.id == nativeId }
        if (prefs.getBoolean("removed_$nativeId", false)) return false
        return prefs.getBoolean("enabled_$nativeId", p?.defaultEnabled ?: false)
    }

    /** 按插件 id 设置启用状态 */
    fun setEnabled(id: String, enabled: Boolean) {
        val nativeId = resolveNativeId(id)
        prefs.edit().putBoolean("enabled_$nativeId", enabled).apply()
        if (enabled) {
            prefs.edit().putBoolean("removed_$nativeId", false).apply()
        }
    }

    /** 按官方 bundle 名设置启用状态（对应 dsh-plugin-manager.setBundleEnabled） */
    fun setBundleEnabled(bundle: String, enabled: Boolean) {
        setEnabled(resolveNativeId(bundle), enabled)
    }

    /** 卸载插件（对应 dsh-plugin-manager.removeBundle）：仅可选插件可卸载 = 停用并从启用列表移除 */
    fun removeBundle(bundle: String) {
        val nativeId = resolveNativeId(bundle)
        val p = builtin.firstOrNull { it.id == nativeId }
        if (p == null || p.optional) {
            prefs.edit()
                .putBoolean("enabled_$nativeId", false)
                .putBoolean("removed_$nativeId", true)
                .apply()
        }
    }

    /** 读取插件配置（缺省用默认值） */
    fun config(id: String): Map<String, String> {
        val nativeId = resolveNativeId(id)
        val p = byId(nativeId) ?: return emptyMap()
        val stored = configPrefs.getString("config_$nativeId", null)
            ?: return p.configItems.associate { it.key to it.defaultValue }
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: JSONObject()
        return p.configItems.associate { item ->
            item.key to json.optString(item.key, item.defaultValue)
        }
    }

    /** 保存插件配置（动态表单提交） */
    fun saveConfig(id: String, values: Map<String, String>) {
        val nativeId = resolveNativeId(id)
        byId(nativeId) ?: return
        val merged = config(nativeId).toMutableMap().apply { putAll(values) }
        configPrefs.edit().putString("config_$nativeId", JSONObject(merged).toString()).apply()
    }

    /** 当前所有启用插件（内置 + 已安装的可选/市场插件） */
    private fun enabledPlugins(): List<DshPlugin> {
        val result = builtin.filter { isEnabled(it.id) }.toMutableList()
        for (m in marketIndex()) {
            if (!m.builtin && isEnabled(m.id) && result.none { it.id == m.id }) result.add(m)
        }
        return result
    }

    /** 拼接所有启用插件注入系统提示词的片段（供 AgentEngine 使用） */
    fun enabledPrompts(): String =
        enabledPlugins()
            .map { it.prompt.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

    /** 插件市场索引：官方 72 个 @deepseek-ai/dsh-* 插件 + 本地可选插件（含配置项与启用状态） */
    fun marketIndex(): List<DshPlugin> {
        val result = mutableListOf<DshPlugin>()
        val seenOfficial = mutableSetOf<String>()
        for (meta in OFFICIAL_MARKET) {
            // 官方插件若在本地有内置实现，则继承其工具/配置/提示词；否则作为可安装插件
            val native = builtin.firstOrNull { it.officialId == meta.id && it.builtin }
            val entry = if (native != null) {
                native.copy(
                    id = meta.id,
                    name = meta.name,
                    description = meta.description,
                    category = meta.category,
                    bundleName = meta.id,
                    officialId = meta.id,
                    builtin = true,
                    optional = false
                )
            } else {
                DshPlugin(
                    id = meta.id,
                    name = meta.name,
                    description = meta.description,
                    category = meta.category,
                    version = "0.2.1",
                    bundleName = meta.id,
                    officialId = meta.id,
                    builtin = false,
                    optional = true
                )
            }
            entry.enabled = isEnabled(entry.id)
            result.add(entry)
            seenOfficial.add(meta.id)
        }
        // 本地可选插件（不在官方 72 清单内的扩展，如 root/shizuku 的独立条目）
        for (p in builtin) {
            if (p.optional && p.officialId !in seenOfficial) {
                val copy = p.copy().apply { enabled = isEnabled(p.id) }
                result.add(copy)
            }
        }
        return result
    }

    /** 插件目录 JSON（给协议层 / UI）：含配置项、当前配置值与启用状态 */
    fun pluginCatalog(): String {
        val arr = buildJsonArray {
            marketIndex().forEach { p ->
                add(buildJsonObject {
                    put("id", p.id)
                    put("bundleName", p.bundleName.ifBlank { p.officialId })
                    put("name", p.name)
                    put("description", p.description)
                    put("category", p.category.name)
                    put("version", p.version)
                    put("author", p.author)
                    put("enabled", p.enabled)
                    put("builtin", p.builtin)
                    put("optional", p.optional)
                    put("dependencies", buildJsonArray { p.dependencies.forEach { add(JsonPrimitive(it)) } })
                    put("tools", buildJsonArray { p.tools.forEach { add(JsonPrimitive(it)) } })
                    put("api", buildJsonArray { p.api.forEach { add(JsonPrimitive(it)) } })
                    put("prompt", p.prompt)
                    put("config", buildJsonObject {
                        config(p.id).forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                    put("configItems", buildJsonArray {
                        p.configItems.forEach { item ->
                            add(buildJsonObject {
                                put("key", item.key)
                                put("label", item.label)
                                put("type", item.type.name)
                                put("default", item.defaultValue)
                                put("description", item.description)
                                put("options", buildJsonArray { item.options.forEach { add(JsonPrimitive(it)) } })
                            })
                        }
                    })
                })
            }
        }
        return arr.toString()
    }

    /** 返回当前启用插件暴露的所有工具名（供 ToolRegistry 过滤） */
    fun enabledTools(): Set<String> =
        enabledPlugins().flatMap { it.tools }.toSet()

    /** 返回当前启用插件注册的所有 API 端点名（供前端协议层使用） */
    fun enabledApi(): Set<String> =
        enabledPlugins().flatMap { it.api }.toSet()

    /** 注册插件服务实例（对应 cordis ctx.provide） */
    fun registerService(id: String, service: Any) {
        services[id] = service
    }

    /** 获取插件服务实例（对应 cordis ctx.inject） */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(id: String): T? = services[id] as? T

    // ---------- 生命周期（对应 cordis 插件 apply(ctx) / start / stop） ----------

    /** 注册某个插件的生命周期实现（在启停时调用 onStart/onStop） */
    fun registerLifecycle(id: String, lifecycle: PluginLifecycle) {
        lifecycles[resolveNativeId(id)] = lifecycle
    }

    /** 启动所有启用插件的生命周期（应用启动时调用） */
    fun startPlugins() {
        for (p in enabledPlugins()) {
            val lifecycle = lifecycles[p.id] ?: continue
            runCatching { lifecycle.onStart(PluginContext(this, p.id)) }
        }
    }

    /** 停止所有启用插件的生命周期（应用退出/停用插件时调用） */
    fun stopPlugins() {
        for (p in enabledPlugins()) {
            val lifecycle = lifecycles[p.id] ?: continue
            runCatching { lifecycle.onStop(PluginContext(this, p.id)) }
        }
    }

    // ---------- 事件总线（对应 cordis ctx.on / ctx.emit） ----------

    /** 订阅事件 */
    fun on(event: String, pluginId: String, handler: (JSONObject) -> Unit) {
        events.getOrPut(event) { mutableListOf() }.add(pluginId to handler)
    }

    /** 发布事件，同步调用所有订阅者 */
    fun emit(event: String, payload: JSONObject) {
        events[event]?.toList()?.forEach { (_, handler) ->
            runCatching { handler(payload) }
        }
    }

    /** 插件配置描述（对应 configForms.describe）：返回配置项清单 */
    fun describeConfig(id: String): List<PluginConfigItem> {
        return byId(resolveNativeId(id))?.configItems ?: emptyList()
    }

    /** 官方 72 个插件市场条目（id / 可读名 / 描述 / 分类），数据源：index.html 的 __DSH_BOOT__ 插件组合清单 */
    private data class OfficialMarketMeta(
        val id: String,
        val name: String,
        val description: String,
        val category: PluginCategory
    )

    private companion object {
        val OFFICIAL_MARKET: List<OfficialMarketMeta> = listOf(
            // ---------- API / 后端控制器 ----------
            OfficialMarketMeta("@deepseek-ai/dsh-api-gateway", "API 网关", "DSH 客户端 API 网关：统一转发前端协议层请求到各插件服务。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-api-job-controller", "后台任务控制", "后台任务（jobs）控制器：运行 / 列出 / 查询 shell 后台任务。", PluginCategory.TOOL),
            OfficialMarketMeta("@deepseek-ai/dsh-api-remote-access", "远程访问", "远程访问控制器：管理 Web 客户端与本机服务的远程连接。", PluginCategory.SERVICE),
            OfficialMarketMeta("@deepseek-ai/dsh-api-remotes", "远程服务桥", "Remote 服务桥：把插件 API 暴露给前端浏览器协议层。", PluginCategory.SERVICE),
            OfficialMarketMeta("@deepseek-ai/dsh-api-session-controller", "会话控制", "会话控制器：多会话 / 消息历史 / 事件日志的持久化。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-api-terminal-controller", "终端控制", "终端控制器：在设备上执行 shell 命令并返回输出。", PluginCategory.TOOL),
            OfficialMarketMeta("@deepseek-ai/dsh-api-workspace-controller", "工作区控制", "工作区控制器：目录浏览 / 归档 / 工作区操作。", PluginCategory.SERVICE),
            OfficialMarketMeta("@deepseek-ai/dsh-api-workspace-files", "工作区文件", "工作区文件工具：列目录 / 读文件 / 写文件。", PluginCategory.TOOL),

            // ---------- 客户端运行时 ----------
            OfficialMarketMeta("@deepseek-ai/dsh-client-connection", "连接管理", "客户端连接管理：WebSocket 通道与断线恢复。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-client-file-upload", "文件上传", "客户端文件上传通道。", PluginCategory.SERVICE),
            OfficialMarketMeta("@deepseek-ai/dsh-client-hmr", "热更新", "开发期模块热更新（HMR）。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-client-locale", "本地化", "字典注册表与语言偏好：界面文案的 i18n。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-client-modules", "模块加载器", "客户端模块系统：加载 / 注册前端插件 bundle。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-client-resources", "资源服务", "客户端静态资源加载与缓存。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-client-runtime", "客户端运行时", "客户端运行时基础服务。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-client-shortcuts", "快捷键服务", "全局快捷键定义与分发。", PluginCategory.SYSTEM),

            // ---------- UI 插件 ----------
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-agent-preset", "智能体预设", "新建会话时的智能体预设选择控件。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-approval", "审批面板", "工具调用的审批请求与确认面板。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-attachment", "附件面板", "会话消息附件（图片 / 文件）展示与上传。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-brand-official", "品牌标识", "官方品牌标识与产品文案。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-chat", "对话界面", "主聊天界面：消息流、输入框、工具调用渲染。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-commands", "命令面板", "斜杠命令与快速操作面板。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-conversation", "会话界面", "会话列表、消息编排与本地回显。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-cordis", "Cordis 面板", "Cordis 插件运行时状态可视化面板。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-deepseek-model-label-1017", "模型标签", "DeepSeek 模型名称角标（1017 版）。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-deliverables", "交付物面板", "会话交付物（文件 / 产物）展示。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-directory-picker-browse", "目录选择器", "工作区目录浏览与选择器。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-goal", "目标面板", "长期目标（goal）的展示与编辑。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-input-trigger", "输入触发", "输入框触发与快捷输入。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-jobs", "任务面板", "后台任务运行状态面板。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-layout", "布局系统", "客户端整体布局与面板排布。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-message-feedback", "消息反馈", "消息点赞 / 点踩与反馈收集。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-model-selection", "模型选择", "模型选择器与切换控件。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-open-in-app", "应用内打开", "在系统应用中打开链接 / 文件。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-permission-presets", "权限预设", "权限预设：批量授予 / 检查系统权限。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-plan", "计划面板", "任务计划（plan）的展示与编辑。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-plugin-manager", "插件管理", "插件管理器：已安装 / 市场 / 安装与卸载对话框。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-primitives", "UI 基础组件", "客户端 UI 基础组件库（按钮 / 弹窗 / 终端块等）。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-reference", "参考引用", "消息中的参考来源与引用展示。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-renderer", "渲染器", "Markdown / 代码 / 消息内容渲染器。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-schedule", "定时任务", "定时提醒（schedule）的展示与管理。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-secret-mask", "密钥掩码", "敏感信息（密钥 / Token）的掩码显示。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-session", "会话面板", "会话头部、模型与工作区信息。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings", "设置框架", "设置页框架：命名空间描述与表单渲染。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-account", "账户设置", "账户与登录信息设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-agent-loop", "Agent 循环设置", "Agent 循环（最大轮数 / 行为）设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-general", "通用设置", "通用设置页：语言 / 外观 / 偏好。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-models", "模型设置", "模型服务商与模型配置设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-plugin-inventory", "插件清单设置", "已加载插件清单（只读）设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-plugins", "插件设置", "插件启用 / 停用设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-session-log", "会话日志设置", "会话日志记录与导出设置。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-shell", "Shell 设置", "终端 Shell 环境设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-subagent", "子代理设置", "子代理目录与默认子代理设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-settings-web-search", "网页搜索设置", "网页搜索服务与 API 设置页。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-shortcuts", "快捷键界面", "快捷键列表与编辑界面。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-sidebar", "侧栏", "左侧会话导航栏。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-sidebar-documentpreview", "文档预览", "侧栏文档预览（Office / PDF）。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-sidebar-files", "文件侧栏", "侧栏文件浏览器。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-sidebar-right", "右侧栏", "右侧信息栏：会话详情 / 工具状态。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-sidebar-terminal", "终端侧栏", "侧栏内嵌终端。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-skill", "技能面板", "技能（Skill）的加载与展示。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-slots", "槽位系统", "前端槽位（slots）注册与注入系统。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-subagent", "子代理面板", "子代理卡片与目录展示。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-theme", "主题", "主题注册表与明暗偏好。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-tool", "工具面板", "工具调用详情与目录展示。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-trajectory", "轨迹面板", "请求轨迹（trajectory）时间线。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-user-questions", "用户提问", "会话中的用户问题展示与追问。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-workflow-run", "工作流运行", "工作流运行状态面板。", PluginCategory.UI),
            OfficialMarketMeta("@deepseek-ai/dsh-client-ui-workspace", "工作区界面", "工作区文件浏览界面。", PluginCategory.UI),

            // ---------- 其他核心 ----------
            OfficialMarketMeta("@deepseek-ai/dsh-cordis-client-runner", "Cordis 客户端运行时", "在浏览器中运行 Cordis 插件客户端的运行时。", PluginCategory.SYSTEM),
            OfficialMarketMeta("@deepseek-ai/dsh-session-log-export", "会话日志导出", "将会话日志导出为文件。", PluginCategory.SERVICE),
            OfficialMarketMeta("@deepseek-ai/dsh-typert-registry", "Typert 注册表", "Typert 类型注册表：插件 API 的类型描述与校验。", PluginCategory.SYSTEM)
        )
    }
}