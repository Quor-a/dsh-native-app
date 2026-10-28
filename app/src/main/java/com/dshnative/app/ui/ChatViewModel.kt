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
import com.dshnative.app.model.ChatMessage
import com.dshnative.app.model.Conversation
import com.dshnative.app.tools.ToolRegistry
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConversationStore(application)
    private val config = AppConfig(application)
    private val tools = ToolRegistry(application)

    private val _conversations = mutableStateListOf<Conversation>()
    val conversations: List<Conversation> = _conversations

    var currentId by mutableStateOf("")
        private set

    private val _busy = mutableStateOf(false)
    val busy: State<Boolean> = _busy

    init {
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
        viewModelScope.launch {
            try {
                if (config.apiKey.isEmpty()) {
                    appendAssistant("尚未配置 API Key，请在右上角设置中填写。")
                    return@launch
                }
                val llm = LlmClient(config.baseUrl, config.apiKey, config.model)
                val engine = AgentEngine(llm, tools)
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