package com.dshnative.app.ui

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dshnative.app.agent.AgentEngine
import com.dshnative.app.config.AppConfig
import com.dshnative.app.data.ConversationStore
import com.dshnative.app.llm.ApiMessage
import com.dshnative.app.llm.LlmClient
import com.dshnative.app.model.ApiProviderConfigs
import com.dshnative.app.model.ApiProviderType
import com.dshnative.app.model.ChatMessage
import com.dshnative.app.model.Conversation
import com.dshnative.app.model.QuroModelConfigRepository
import com.dshnative.app.model.QuroModelListResult
import com.dshnative.app.plugins.PluginRegistry
import com.dshnative.app.tools.ToolRegistry
import com.dshnative.app.termux.TermuxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConversationStore(application)
    private val config = AppConfig(application)
    private val modelRepo = QuroModelConfigRepository(application)
    private val tools = ToolRegistry(application)
    private val plugins = PluginRegistry(application)

    private val _conversations = mutableStateListOf<Conversation>()
    val conversations: List<Conversation> = _conversations

    var currentId by mutableStateOf("")
        private set

    private val _busy = mutableStateOf(false)
    val busy: State<Boolean> = _busy

    private val _toolStatus = mutableStateOf("")
    val toolStatus: State<String> = _toolStatus

    // 当前模型（真实持久化配置的镜像，供聊天页顶部标签展示）
    private val _model = MutableStateFlow(modelRepo.load().model)
    val model: StateFlow<String> = _model.asStateFlow()

    init {
        // 后台解压内置 Termux（约 50MB，首次启动后台完成，终端工具立即可用）
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TermuxManager.init(application)
                TermuxManager.ensureExtracted(application)
            }
        }

        val loaded = store.loadAll()
        if (loaded.isEmpty()) {
            val c = Conversation(title = "新对话")
            _conversations.add(c)
            currentId = c.id
        } else {
            _conversations.addAll(loaded)
            currentId = loaded.last().id
        }
    }

    val currentConversation: Conversation?
        get() = _conversations.firstOrNull { it.id == currentId }

    fun newConversation() {
        if (_busy.value) return
        val c = Conversation(title = "新对话")
        _conversations.add(c)
        currentId = c.id
        persist()
    }

    fun switchConversation(id: String) {
        if (_busy.value) return
        if (_conversations.any { it.id == id }) currentId = id
    }

    fun deleteConversation(id: String) {
        if (_busy.value) return
        val idx = _conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        _conversations.removeAt(idx)
        if (currentId == id) {
            currentId = _conversations.lastOrNull()?.id ?: ""
        }
        persist()
    }

    fun renameConversation(id: String, title: String) {
        val idx = _conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        _conversations[idx] = _conversations[idx].copy(title = title)
        persist()
    }

    /**
     * 切换模型：写回 QuroModelConfigRepository 持久化配置，并同步 Compose 状态。
     * 后续 [send] 每次仍从 repo 读取最新配置，因此切换后立即生效。
     */
    fun switchModel(model: String) {
        val name = model.trim()
        if (name.isEmpty()) return
        val cfg = modelRepo.load()
        if (cfg.model == name) return
        modelRepo.save(cfg.copy(model = name))
        _model.value = name
    }

    /** 从 repo 重新同步当前模型（从模型配置页返回后调用，避免展示陈旧值） */
    fun syncModelFromRepo() {
        _model.value = modelRepo.load().model
    }

    /**
     * 可用模型列表：优先读取按当前 baseUrl 缓存的 /models 结果；
     * 缓存为空/失效时用当前服务商默认模型 + 常见模型名兜底，并确保当前模型在列表内。
     */
    fun availableModels(): List<String> {
        val cfg = modelRepo.load()
        val cachedIds = when (val cached = modelRepo.loadModelListCache(cfg.baseUrl)) {
            is QuroModelListResult.Success -> cached.models.map { it.id }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        val default = try {
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.valueOf(cfg.provider))
        } catch (_: Exception) {
            ""
        }
        val fallback = mutableListOf<String>().apply {
            if (default.isNotBlank()) add(default)
            addAll(COMMON_MODEL_FALLBACKS)
        }.distinct()
        val merged = (cachedIds + fallback).distinct()
        return if (cfg.model.isNotBlank() && cfg.model !in merged) {
            listOf(cfg.model) + merged
        } else {
            merged
        }
    }

    companion object {
        private val COMMON_MODEL_FALLBACKS = listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "gpt-4o",
            "gpt-4o-mini",
            "claude-3-5-sonnet-20241022",
            "gemini-2.0-flash"
        )
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _busy.value) return
        val ci = _conversations.indexOfFirst { it.id == currentId }
        if (ci < 0) return
        val conv = _conversations[ci]
        // 自动命名：第一条用户消息前 20 字
        val title = if (conv.title == "新对话" && conv.messages.isEmpty()) content.take(20) else conv.title
        updateConversation(ci, conv.copy(title = title, messages = conv.messages + ChatMessage("user", content)))
        // 添加 assistant 占位（流式输出目标）
        updateConversation(ci, _conversations[ci].copy(messages = _conversations[ci].messages + ChatMessage("assistant", "")))
        _busy.value = true
        _toolStatus.value = ""
        viewModelScope.launch {
            try {
                val cfg = modelRepo.load()  // 每次发送前加载最新模型配置
                if (cfg.apiKey.isEmpty()) {
                    appendAssistant("尚未配置 API Key，请在模型配置中填写。")
                    return@launch
                }
                // 模型名兜底：用户未填或服务商默认模型名为空时，最终用 deepseek-chat，杜绝 model is required
                val model = cfg.model.trim().ifBlank {
                    val default = try {
                        ApiProviderConfigs.getDefaultModelName(ApiProviderType.valueOf(cfg.provider))
                    } catch (_: Exception) {
                        ""
                    }
                    default.ifBlank { "deepseek-chat" }
                }
                val llm = LlmClient(cfg.baseUrl, cfg.apiKey, model)
                val engine = AgentEngine(llm, tools, plugins)
                val apiMessages = buildApiMessages()
                engine.run(
                    history = apiMessages,
                    onDelta = { delta ->
                        val i = _conversations.indexOfFirst { it.id == currentId }
                        if (i < 0) return@run
                        val msgs = _conversations[i].messages.toMutableList()
                        val last = msgs.lastOrNull() ?: return@run
                        msgs[msgs.size - 1] = last.copy(content = last.content + delta)
                        updateConversation(i, _conversations[i].copy(messages = msgs))
                    },
                    onToolStart = { name ->
                        _toolStatus.value = toolStatusLabel(name)
                    }
                )
            } catch (e: Exception) {
                appendAssistant("错误：${e.message}")
            } finally {
                _busy.value = false
                persist()
            }
        }
    }

    private fun toolStatusLabel(name: String): String = when (name) {
        "android_shell_exec" -> "执行了命令"
        "android_ui_tree" -> "读取了屏幕"
        "android_screenshot" -> "截取了屏幕"
        "android_ui_click" -> "点击了屏幕"
        "android_ui_input" -> "输入了文本"
        "android_ui_swipe" -> "滑动了屏幕"
        "android_ui_global" -> "执行了全局动作"
        "android_app_list" -> "读取了应用列表"
        "android_file_list" -> "浏览了文件"
        "android_file_read" -> "读取了文件"
        "android_file_write" -> "写入了文件"
        "android_clipboard_get" -> "读取了剪贴板"
        "android_clipboard_set" -> "写入了剪贴板"
        "android_web_fetch" -> "抓取了网页"
        "android_browser_open" -> "打开了浏览器"
        "android_system_info" -> "读取了系统信息"
        else -> "调用了 $name"
    }

    private fun appendAssistant(text: String) {
        val i = _conversations.indexOfFirst { it.id == currentId }
        if (i < 0) return
        val msgs = _conversations[i].messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && last.role == "assistant" && last.content.isEmpty()) {
            msgs[msgs.size - 1] = last.copy(content = text)
        } else {
            msgs.add(ChatMessage("assistant", text))
        }
        updateConversation(i, _conversations[i].copy(messages = msgs))
    }

    private fun buildApiMessages(): List<ApiMessage> {
        val conv = currentConversation ?: return emptyList()
        val list = mutableListOf<ApiMessage>()
        for (m in conv.messages) {
            when (m.role) {
                "user" -> list.add(ApiMessage("user", m.content))
                "assistant" -> if (m.content.isNotEmpty()) list.add(ApiMessage("assistant", m.content))
            }
        }
        return list
    }

    private fun updateConversation(index: Int, conv: Conversation) {
        if (index in _conversations.indices) _conversations[index] = conv
    }

    private fun persist() {
        store.saveAll(_conversations.toList())
    }
}