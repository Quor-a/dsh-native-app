package com.dshnative.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 LLM 流式客户端。
 * 支持 /chat/completions 流式返回：content 增量 + tool_calls 增量。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    model: String
) {
    /** 最终兜底：模型名绝对不允许为空，否则 OpenAI 兼容 API 返回 HTTP 400 model is required */
    private val model: String = model.trim().ifBlank { "deepseek-chat" }
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class StreamResult(
        val content: String,
        val toolCalls: List<ApiToolCall>
    )

    /** 流式请求（异步回调） */
    fun streamChat(
        messages: List<ApiMessage>,
        tools: List<ApiToolDef>?,
        onDelta: (String) -> Unit,
        onToolCallDelta: (List<ApiToolCall>) -> Unit,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val bodyStr = buildChatBody(model, messages, tools)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("网络错误: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val err = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    onError("HTTP ${response.code}: ${err.take(500)}")
                    response.close()
                    return
                }
                val source = response.body?.source()
                if (source == null) {
                    onError("响应无内容")
                    response.close()
                    return
                }
                val sb = StringBuilder()
                val toolCalls = mutableListOf<ApiToolCall>()
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val chunk = try {
                            json.decodeFromString(ChatChunk.serializer(), data)
                        } catch (_: Exception) {
                            continue
                        }
                        for (choice in chunk.choices ?: emptyList()) {
                            val delta = choice.delta ?: continue
                            delta.content?.let {
                                sb.append(it)
                                onDelta(it)
                            }
                            for (tc in delta.tool_calls ?: emptyList()) {
                                while (toolCalls.size <= tc.index) {
                                    toolCalls.add(ApiToolCall("", function = ApiFunctionCall("", "")))
                                }
                                val existing = toolCalls[tc.index]
                                val id = tc.id ?: existing.id
                                val name = if (existing.function.name.isEmpty()) (tc.function?.name ?: "") else existing.function.name
                                val args = tc.function?.arguments ?: ""
                                toolCalls[tc.index] = ApiToolCall(
                                    id, "function",
                                    ApiFunctionCall(name, existing.function.arguments + args)
                                )
                                onToolCallDelta(toolCalls.toList())
                            }
                        }
                    }
                    onDone(StreamResult(sb.toString(), toolCalls))
                } catch (e: Exception) {
                    onError("流解析失败: ${e.message}")
                } finally {
                    response.close()
                }
            }
        })
    }

    /** 阻塞式调用（协程内使用），返回完整内容 + 工具调用 */
    suspend fun chat(messages: List<ApiMessage>, tools: List<ApiToolDef>?): StreamResult {
        val latch = CountDownLatch(1)
        var result = StreamResult("", emptyList())
        var error: String? = null
        streamChat(
            messages = messages,
            tools = tools,
            onDelta = {},
            onToolCallDelta = {},
            onDone = {
                result = it
                latch.countDown()
            },
            onError = {
                error = it
                latch.countDown()
            }
        )
        withContext(Dispatchers.IO) { latch.await() }
        error?.let { throw RuntimeException(it) }
        return result
    }

    /** 手动构建请求体：确保 tools 数组每项的 type 严格为 "function"（兼容 OpenRouter/中转等严格校验的 API） */
    private fun buildChatBody(model: String, messages: List<ApiMessage>, tools: List<ApiToolDef>?): String {
        val messagesJson = try {
            json.encodeToString(messages)
        } catch (_: Exception) {
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiMessage.serializer()), messages)
        }
        return buildString {
            append("""{"model":""").append(jsonString(model))
            append(""","messages":""").append(messagesJson)
            append(""","stream":true""")
            if (!tools.isNullOrEmpty()) {
                append(""","tools":[""")
                append(tools.joinToString(",") { t ->
                    append("""{"type":"function","function":{"name":""")
                    append(jsonString(t.function.name))
                    append(""","description":""")
                    append(jsonString(t.function.description))
                    append(""","parameters":""")
                    append(t.function.parameters.toString())
                    append("""}}""")
                })
                append("]")
            }
            append("}")
        }
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}