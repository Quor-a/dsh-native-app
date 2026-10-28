package com.dshnative.app.ui

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshnative.app.agent.AgentEngine
import com.dshnative.app.config.AppConfig
import com.dshnative.app.llm.ApiMessage
import com.dshnative.app.llm.LlmClient
import com.dshnative.app.model.ChatMessage
import com.dshnative.app.tools.ToolRegistry
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val messages = mutableStateListOf<ChatMessage>()
    private val _busy = mutableStateOf(false)
    val busy: State<Boolean> = _busy

    private val config = AppConfig(application)
    private val tools = ToolRegistry(application)

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _busy.value) return
        messages.add(ChatMessage("user", content))
        _busy.value = true
        viewModelScope.launch {
            try {
                val apiKey = config.apiKey
                if (apiKey.isEmpty()) {
                    messages.add(ChatMessage("assistant", "尚未配置 API Key，请在设置中填写（右上角设置按钮）。"))
                    return@launch
                }
                val llm = LlmClient(config.baseUrl, config.apiKey, config.model)
                val engine = AgentEngine(llm, tools)
                val apiMessages = buildApiMessages()
                val assistantIndex = messages.size
                messages.add(ChatMessage("assistant", ""))
                engine.run(
                    history = apiMessages,
                    onDelta = { delta ->
                        val cur = messages[assistantIndex].content
                        messages[assistantIndex] = messages[assistantIndex].copy(content = cur + delta)
                    }
                )
            } catch (e: Exception) {
                messages.add(ChatMessage("assistant", "错误：${e.message}"))
            } finally {
                _busy.value = false
            }
        }
    }

    /** 把 UI 消息转为 LLM 消息（跳过空 assistant 占位与本地提示） */
    private fun buildApiMessages(): List<ApiMessage> {
        val list = mutableListOf<ApiMessage>()
        for (m in messages) {
            when (m.role) {
                "user" -> list.add(ApiMessage("user", m.content))
                "assistant" -> if (m.content.isNotEmpty()) list.add(ApiMessage("assistant", m.content))
            }
        }
        return list
    }
}