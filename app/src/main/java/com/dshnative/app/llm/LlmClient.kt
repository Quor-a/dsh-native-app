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
    private val model: String
) {
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
        val requestBody = ChatRequest(model, messages, tools, stream = true)
        val bodyStr = json.encodeToString(ChatRequest.serializer(), requestBody)
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
}