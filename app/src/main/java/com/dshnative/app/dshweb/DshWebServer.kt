package com.dshnative.app.dshweb

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * DSH 前端最小协议层本地服务器。
 *
 * 职责：
 * 1. 静态服务 app/src/main/assets/dsh/ 下的 DSH Web 前端（含 /plugins/ combo 脚本）；
 * 2. 实现 /api/<endpoint> 的 HTTP 一元 RPC 桥（Typert Remote client-request/server-response 信封）；
 * 3. 实现 /api/remote.mux WebSocket 多路复用流（$events + session/follow 等）。
 */
class DshWebServer : NanoWSD(HOST, PORT) {

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8756
        private const val TAG = "DshWebServer"
        private const val COOKIE_PREFIX = "dsh-auth-"
        private const val HOME_PATH = "/data/data/com.dshnative.app/files"
        private const val ASSET_ROOT = "dsh"

        @OptIn(ExperimentalSerializationApi::class)
        private val rpcJson = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }

    @Serializable
    data class RpcResult(
        val ok: Boolean,
        val value: JsonElement? = null,
        val error: RpcError? = null
    )

    @Serializable
    data class RpcError(
        val code: String,
        val message: String,
        val details: JsonObject = JsonObject(emptyMap())
    )

    @Serializable
    data class RpcResponse(
        val type: String = "server-response",
        val rpcId: String,
        val result: RpcResult
    )

    /** 一个会话的状态：日志游标、标题、运行状态，供 session/list 与 session/follow 使用。 */
    class SessionState(val id: String) {
        @Volatile
        var seq: Long = 0
            private set

        @Volatile
        var turn: Long = 1
            private set

        @Volatile
        var title: String = "新会话"

        @Volatile
        var blank: Boolean = true

        @Volatile
        var running: Boolean = false

        @Volatile
        var lastPromptAt: JsonElement? = null

        val createdAt: Long = System.currentTimeMillis()

        @Synchronized
        fun nextSeq(): Long {
            seq += 1
            return seq
        }
    }

    /** session/follow 流上的订阅关系。 */
    data class FollowRegistration(val socket: MuxWebSocket, val streamId: String)

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val followRegistrations = ConcurrentHashMap<String, MutableSet<FollowRegistration>>()
    private val sockets = ConcurrentHashMap.newKeySet<MuxWebSocket>()

    private val cookieName = COOKIE_PREFIX + randomToken()
    private val cookieValue = "v1.${randomToken()}.${randomToken()}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    fun start(context: Context) {
        if (appContext != null) {
            return
        }
        appContext = context.applicationContext
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "DSH web server listening on http://$HOST:$PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start DSH web server", e)
        }
    }

    fun stopServer() {
        scope.cancel()
        stop()
        appContext = null
    }

    // ---------------------------------------------------------------------
    // HTTP 路由（非 WebSocket 请求）
    // ---------------------------------------------------------------------

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        return when {
            method == NanoHTTPD.Method.GET && uri == "/" -> serveIndex(session)
            method == NanoHTTPD.Method.GET && uri.startsWith("/plugins/") -> serveCombo(session, uri)
            method == NanoHTTPD.Method.POST && uri.startsWith("/api/") -> serveApi(session, uri)
            method == NanoHTTPD.Method.GET && uri.startsWith("/assets/") -> serveAsset(uri.removePrefix("/"))
            method == NanoHTTPD.Method.GET && (uri == "/manifest.webmanifest" || uri == "/favicon.svg" || uri == "/favicon-dark.svg") ->
                serveAsset(uri.removePrefix("/"))
            method == NanoHTTPD.Method.GET -> serveAsset(uri.removePrefix("/"))
            else -> newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "not found"
            )
        }
    }

    private fun serveIndex(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val response = serveAsset("$ASSET_ROOT/index.html")
        response.addHeader(
            "Set-Cookie",
            "$cookieName=$cookieValue; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000"
        )
        return response
    }

    private fun serveCombo(session: NanoHTTPD.IHTTPSession, uri: String): NanoHTTPD.Response {
        val assetPath = comboAssetFor(uri)
        if (assetPath == null) {
            Log.w(TAG, "Unknown combo request: $uri")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "combo not found"
            )
        }
        return serveAsset(assetPath)
    }

    private fun comboAssetFor(uri: String): String? {
        return when {
            uri.startsWith("/plugins/??@deepseek-ai/dsh-client-modules/client.js") ->
                "$ASSET_ROOT/plugins/combo-bootstrap.js"
            uri.startsWith("/plugins/??@deepseek-ai/dsh-api-gateway/client.js") ->
                "$ASSET_ROOT/plugins/combo-application-1.js"
            uri.startsWith("/plugins/??@deepseek-ai/dsh-client-ui-trajectory/client.js") ->
                "$ASSET_ROOT/plugins/combo-application-2.js"
            else -> null
        }
    }

    private fun serveAsset(assetPath: String): NanoHTTPD.Response {
        val context = appContext
            ?: return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                NanoHTTPD.MIME_PLAINTEXT,
                "server not started"
            )
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeFor(assetPath),
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } catch (e: IOException) {
            Log.w(TAG, "Asset not found: $assetPath", e)
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "not found"
            )
        }
    }

    private fun mimeFor(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".json") -> "application/json; charset=utf-8"
            path.endsWith(".webmanifest") -> "application/manifest+json"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
    }

    // ---------------------------------------------------------------------
    // HTTP RPC 桥
    // ---------------------------------------------------------------------

    private fun serveApi(session: NanoHTTPD.IHTTPSession, uri: String): NanoHTTPD.Response {
        if (!hasValidCookie(session)) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                NanoHTTPD.MIME_PLAINTEXT,
                "unauthorized"
            )
        }
        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read body", e)
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                NanoHTTPD.MIME_PLAINTEXT,
                "bad request"
            )
        }

        val request = try {
            rpcJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON body", e)
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                NanoHTTPD.MIME_PLAINTEXT,
                "invalid json"
            )
        }

        val rpcId = request["rpcId"]?.jsonPrimitive?.contentOrNull ?: "invalid-request"
        val method = request["method"]?.jsonPrimitive?.contentOrNull ?: ""
        val endpoint = uri.removePrefix("/api/")
        if (request["type"]?.jsonPrimitive?.contentOrNull != "client-request" || method != endpoint) {
            return rpcResponse(RpcResponse(rpcId = rpcId, result = errorResult(
                "gateway/bad-request",
                "method mismatch: expected $endpoint, got $method"
            )))
        }

        val payload = request["payload"]?.jsonObject ?: JsonObject(emptyMap())
        val args = payload["args"]?.jsonObject ?: JsonObject(emptyMap())
        val result = routeRpc(endpoint, args)
        return rpcResponse(RpcResponse(rpcId = rpcId, result = result))
    }

    private fun routeRpc(endpoint: String, args: JsonObject): RpcResult {
        return when (endpoint) {
            "session/list" -> okResult(buildJsonObject {
                put("items", buildJsonArray {
                    sessions.values.forEach { add(sessionListItem(it)) }
                })
            })
            "session/create" -> {
                val request = args["request"]?.jsonObject
                val sessionId = "session-" + UUID.randomUUID()
                sessions[sessionId] = SessionState(sessionId)
                okResult(buildJsonObject {
                    put("sessionId", sessionId)
                    put("agentPreset", request?.get("agentPreset")?.jsonPrimitive?.contentOrNull ?: "standard")
                })
            }
            "session/prompt" -> handlePrompt(args)
            "session/cancel" -> {
                val request = args["request"]?.jsonObject
                val sessionId = request?.get("sessionId")?.jsonPrimitive?.contentOrNull
                if (sessionId != null) {
                    sessions[sessionId]?.running = false
                }
                okResult(buildJsonObject { put("accepted", true) })
            }
            "session/modelCatalog" -> okResult(modelCatalogJson())
            "session/initializeDefaultModel" -> okResult(null)
            "session/page" -> okResult(buildJsonObject {
                put("records", buildJsonArray { })
                put("hasMore", false)
            })
            "session/rename" -> {
                val request = args["request"]?.jsonObject
                val sessionId = request?.get("sessionId")?.jsonPrimitive?.contentOrNull
                val title = request?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
                sessions[sessionId]?.title = title
                okResult(buildJsonObject {
                    put("title", title)
                    put("seq", sessions[sessionId]?.seq ?: 0)
                })
            }
            "session/selectModel" -> {
                val request = args["request"]?.jsonObject
                okResult(buildJsonObject {
                    put("selected", buildJsonObject {
                        put("provider", request?.get("provider")?.jsonPrimitive?.contentOrNull ?: "deepseek")
                        put("model", request?.get("model")?.jsonPrimitive?.contentOrNull ?: "deepseek-chat")
                        put("reasoningEffort", request?.get("reasoningEffort")?.jsonPrimitive?.contentOrNull ?: "medium")
                    })
                })
            }
            "session/projections" -> {
                val request = args["request"]?.jsonObject
                val sessionId = request?.get("sessionId")?.jsonPrimitive?.contentOrNull
                okResult(projectionsJson(sessions[sessionId]))
            }
            "session/fork" -> {
                okResult(buildJsonObject {
                    put("sessionId", "session-" + UUID.randomUUID())
                })
            }
            "session/search" -> okResult(buildJsonObject {
                put("items", buildJsonArray { })
                put("hasMore", false)
            })
            "session/updateQueue" -> okResult(null)
            "session/workspacePathApplications" -> okResult(buildJsonArray { })
            "session/openWorkspacePath" -> okResult(null)
            "session/canOpenWorkspacePath" -> okResult(buildJsonObject { put("available", false) })
            "session/attachment" -> okResult(null)
            "\$events/result" -> okResult(null)
            "commands/list" -> okResult(buildJsonArray { })
            "commands/execute" -> okResult(null)
            "llm/discoverModels" -> okResult(buildJsonArray { })
            "llm/listProviders" -> okResult(buildJsonArray { })
            "llm/listConfigurableProviders" -> okResult(buildJsonArray { })
            "agentPresets/list" -> okResult(buildJsonObject {
                put("presets", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "standard")
                        put("isDefault", true)
                        put("name", "标准")
                        put("description", "默认预设")
                    })
                })
            })
            "agentPresets/read" -> okResult(JsonNull)
            "agentPresets/select" -> okResult(null)
            "workspace/archiveSession" -> okResult(null)
            "workspace/list" -> okResult(buildJsonObject {
                put("items", buildJsonArray { })
            })
            "workspace/get" -> okResult(buildJsonObject {
                put("id", "workspace-" + UUID.randomUUID())
                put("name", "本地工作区")
                put("home", HOME_PATH)
            })
            "fileUploads/upload" -> okResult(buildJsonObject {
                put("receiptId", "local-receipt-" + UUID.randomUUID())
            })
            "goals/list" -> okResult(buildJsonObject { put("items", buildJsonArray { }) })
            "skills/list" -> okResult(buildJsonObject { put("items", buildJsonArray { }) })
            "pluginInventory/list" -> okResult(buildJsonObject { put("items", buildJsonArray { }) })
            "pluginManager/listPlugins" -> okResult(buildJsonArray { })
            "pluginManager/listBundles" -> okResult(buildJsonArray { })
            "pluginMarket/listIndexes" -> okResult(buildJsonArray { })
            "settings/describe" -> okResult(buildJsonObject { })
            "settings/mutate" -> okResult(null)
            "settings/replace" -> okResult(null)
            "settings/update" -> okResult(null)
            "terminal/list" -> okResult(buildJsonObject { put("items", buildJsonArray { }) })
            "terminal/open" -> okResult(null)
            "terminal/write" -> okResult(null)
            "schedule/list" -> okResult(buildJsonObject { put("items", buildJsonArray { }) })
            "schedule/catalog" -> okResult(buildJsonArray { })
            "messageFeedback/list" -> okResult(buildJsonArray { })
            "productAnalytics/enabled" -> okResult(buildJsonObject { put("enabled", false) })
            "account/status" -> okResult(buildJsonObject {
                put("loggedIn", false)
                put("signupEnabled", false)
            })
            "session/logs" -> okResult(buildJsonArray { })
            else -> errorResult(
                "gateway/not-implemented",
                "endpoint not implemented: $endpoint"
            )
        }
    }

    private fun handlePrompt(args: JsonObject): RpcResult {
        val request = args["request"]?.jsonObject
            ?: return errorResult("gateway/arguments-invalid", "missing request")
        val sessionId = request["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("gateway/arguments-invalid", "missing sessionId")
        val requestId = request["requestId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val content = request["content"]?.jsonArray ?: buildJsonArray { }
        val text = content.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
        }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""

        val session = sessions.computeIfAbsent(sessionId) { SessionState(sessionId) }
        session.blank = false
        session.running = true
        session.lastPromptAt = JsonPrimitive(System.currentTimeMillis())
        if (session.title == "新会话") {
            session.title = text.take(24).ifBlank { "新会话" }
        }

        val userSeq = session.nextSeq()
        val currentTurn = session.turn
        broadcastFollow(sessionId, buildJsonObject {
            put("type", "event")
            put("event", buildJsonObject {
                put("type", "user/message")
                put("seq", userSeq)
                put("time", System.currentTimeMillis())
                put("data", buildJsonObject {
                    put("turn", currentTurn)
                    put("step", 0)
                    put("requestId", requestId)
                    put("message", buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        })
                    })
                })
            })
        })

        // 假回复：延迟推送 assistant-stream start/chunk/end + assistant/message 事件。
        scope.launch {
            delay(400)
            val attemptId = UUID.randomUUID().toString()
            broadcastFollow(sessionId, buildJsonObject {
                put("type", "assistant-stream")
                put("frame", buildJsonObject {
                    put("type", "start")
                    put("attemptId", attemptId)
                    put("revision", 0)
                    put("startedAfterSeq", userSeq)
                    put("turn", currentTurn)
                    put("step", 0)
                })
            })

            val reply = buildString {
                append("（DshNative 最小协议层假回复）")
                append("你刚才说：").append(text.ifBlank { "（空消息）" })
                append("。\n\n")
                append("当前运行在 Android 本地 WebView 中，由 NanoHTTPD 提供 /api RPC 桥与 /api/remote.mux WebSocket 流。")
            }
            val chunks = reply.chunked(6)
            chunks.forEachIndexed { index, chunk ->
                delay(120)
                broadcastFollow(sessionId, buildJsonObject {
                    put("type", "assistant-stream")
                    put("frame", buildJsonObject {
                        put("type", "chunk")
                        put("attemptId", attemptId)
                        put("revision", 0)
                        put("index", index)
                        put("time", System.currentTimeMillis())
                        put("chunk", chunk)
                    })
                })
            }

            delay(150)
            val assistantSeq = session.nextSeq()
            session.running = false
            broadcastFollow(sessionId, buildJsonObject {
                put("type", "assistant-stream")
                put("frame", buildJsonObject {
                    put("type", "end")
                    put("attemptId", attemptId)
                    put("revision", 0)
                    put("index", chunks.size)
                    put("outcome", buildJsonObject {
                        put("kind", "committed")
                        put("eventType", "assistant/message")
                        put("seq", assistantSeq)
                    })
                })
            })
            broadcastFollow(sessionId, buildJsonObject {
                put("type", "event")
                put("event", buildJsonObject {
                    put("type", "assistant/message")
                    put("seq", assistantSeq)
                    put("time", System.currentTimeMillis())
                    put("data", buildJsonObject {
                        put("turn", currentTurn)
                        put("step", 0)
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", reply)
                                })
                            })
                        })
                    })
                })
            })
            // 完整体地推进 turn，供下次消息使用。
            session.turn += 1
        }

        return okResult(buildJsonObject { put("accepted", true) })
    }

    // ---------------------------------------------------------------------
    // WebSocket
    // ---------------------------------------------------------------------

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        val uri = handshake.uri
        return if (uri.startsWith("/api/remote.mux")) {
            MuxWebSocket(this, handshake)
        } else {
            MuxWebSocket(this, handshake).also {
                Log.w(TAG, "Rejecting WebSocket upgrade for unexpected path: $uri")
            }
        }
    }

    fun registerSocket(socket: MuxWebSocket) {
        sockets.add(socket)
    }

    fun unregisterSocket(socket: MuxWebSocket) {
        sockets.remove(socket)
        // 清理该 socket 的所有 follow 订阅。
        for (registrations in followRegistrations.values) {
            registrations.removeIf { it.socket == socket }
        }
    }

    fun registerFollow(sessionId: String, socket: MuxWebSocket, streamId: String) {
        followRegistrations.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
            .add(FollowRegistration(socket, streamId))
    }

    fun unregisterFollow(sessionId: String, socket: MuxWebSocket, streamId: String) {
        followRegistrations[sessionId]?.remove(FollowRegistration(socket, streamId))
    }

    fun broadcastFollow(sessionId: String, frame: JsonObject) {
        val registrations = followRegistrations[sessionId] ?: return
        for (registration in registrations) {
            registration.socket.sendItem(registration.streamId, frame)
        }
    }

    fun buildSnapshot(sessionId: String): JsonObject {
        val session = sessions[sessionId]
        return buildJsonObject {
            put("type", "snapshot")
            put("header", buildJsonObject {
                put("version", 4)
                put("id", sessionId)
                put("createdAt", session?.createdAt ?: System.currentTimeMillis())
                put("cwd", HOME_PATH)
                put("isSeeded", false)
                put("delegationDepth", 0)
                put("agentPreset", "standard")
            })
            put("cursor", session?.seq ?: 0)
            put("records", buildJsonArray { })
            put("hasMore", false)
            put("projections", projectionsJson(session))
        }
    }

    fun sendItemTo(socket: MuxWebSocket, streamId: String, value: JsonObject) {
        socket.sendItem(streamId, value)
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------

    private fun hasValidCookie(session: NanoHTTPD.IHTTPSession): Boolean {
        val cookies = session.headers["cookie"] ?: return false
        return cookies.split(';').any { it.trim().startsWith("$cookieName=") }
    }

    private fun rpcResponse(response: RpcResponse): NanoHTTPD.Response {
        val json = rpcJson.encodeToString(RpcResponse.serializer(), response)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            json
        )
    }

    private fun okResult(value: JsonElement?): RpcResult = RpcResult(ok = true, value = value)

    private fun errorResult(
        code: String,
        message: String,
        details: JsonObject = JsonObject(emptyMap())
    ): RpcResult = RpcResult(ok = false, value = null, error = RpcError(code = code, message = message, details = details))

    private fun sessionListItem(session: SessionState): JsonObject = buildJsonObject {
        put("sessionId", session.id)
        put("updatedAt", session.lastPromptAt?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: session.createdAt)
        put("agentAvailable", true)
        put("formatStatus", "current")
        put("running", session.running)
        put("blank", session.blank)
        put("cwd", HOME_PATH)
        put("projections", buildJsonObject {
            put("kind", "sequenced")
            put("asOfSeq", session.seq)
            put("values", projectionValuesJson(session))
        })
    }

    private fun projectionsJson(session: SessionState?): JsonObject = buildJsonObject {
        put("asOfSeq", session?.seq ?: 0)
        put("values", projectionValuesJson(session))
    }

    private fun projectionValuesJson(session: SessionState?): JsonObject = buildJsonObject {
        put("title", session?.title ?: "新会话")
        put("todos", JsonNull)
        put("inbox", buildJsonObject {
            put("next-turn", buildJsonArray { })
            put("next-step", buildJsonArray { })
        })
        put("workingDirectory", HOME_PATH)
        put("agentPreset", "standard")
        put("sessionListMetadata", buildJsonObject {
            put("blank", session?.blank ?: true)
            put("lastPromptAt", session?.lastPromptAt ?: JsonNull)
        })
        put("modelSelection", buildJsonObject {
            put("lastUsed", buildJsonObject {
                put("provider", "deepseek")
                put("model", "deepseek-chat")
                put("reasoningEffort", "medium")
            })
            put("next", buildJsonObject {
                put("provider", "deepseek")
                put("model", "deepseek-chat")
                put("reasoningEffort", "medium")
            })
        })
    }

    private fun modelCatalogJson(): JsonObject = buildJsonObject {
        put("default", buildJsonObject {
            put("provider", "deepseek")
            put("model", "deepseek-chat")
            put("reasoningEffort", "medium")
        })
        put("routableProviders", buildJsonArray { add("deepseek") })
        put("groups", buildJsonArray {
            add(buildJsonObject {
                put("id", "deepseek")
                put("name", "DeepSeek")
                put("models", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "deepseek-chat")
                        put("name", "DeepSeek Chat")
                        put("description", "默认模型")
                        put("reasoning", buildJsonObject {
                            put("efforts", buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "medium")
                                    put("name", "中等推理")
                                })
                            })
                            put("defaultEffort", "medium")
                        })
                    })
                })
            })
        })
        put("failures", buildJsonArray { })
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}