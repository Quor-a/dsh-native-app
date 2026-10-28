package com.dshnative.app.dshweb

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * /api/remote.mux 的 WebSocket 连接。
 *
 * 协议帧（均为文本 JSON）：
 * 客户端 -> 服务端：{type:"open"|"item"|"end"|"cancel", streamId, endpoint, payload}
 * 服务端 -> 客户端：{type:"item"|"end"|"error", streamId, value|error}
 */
class MuxWebSocket(
    private val server: DshWebServer,
    handshake: NanoHTTPD.IHTTPSession
) : NanoWSD.WebSocket(handshake) {

    companion object {
        private const val TAG = "MuxWebSocket"
        private const val HOME_PATH = "/data/data/com.dshnative.app/files"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** 本连接上已打开的流：streamId -> endpoint（及可选 sessionId）。 */
    private data class StreamState(val endpoint: String, val sessionId: String? = null)

    private val streams = ConcurrentHashMap<String, StreamState>()

    override fun onOpen() {
        server.registerSocket(this)
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode,
        reason: String,
        initiatedByRemote: Boolean
    ) {
        for (state in streams.values) {
            if (state.endpoint == "session/follow" && state.sessionId != null) {
                server.unregisterFollow(state.sessionId, this, state.sessionId ?: "")
            }
        }
        streams.clear()
        server.unregisterSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        val text = try {
            message.textPayload
        } catch (e: Exception) {
            Log.w(TAG, "Invalid text frame", e)
            null
        }
        if (text.isNullOrBlank()) {
            return
        }
        val frame = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Invalid frame JSON", e)
            return
        }

        when (frame["type"]?.jsonPrimitive?.contentOrNull) {
            "open" -> handleOpen(frame)
            "item" -> Unit
            "end", "cancel" -> {
                val streamId = frame["streamId"]?.jsonPrimitive?.contentOrNull
                if (streamId != null) {
                    closeStream(streamId)
                }
            }
            else -> Unit
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {
        // 无需处理
    }

    override fun onException(exception: IOException) {
        Log.w(TAG, "WebSocket exception", exception)
    }

    // ---------------------------------------------------------------------
    // 帧处理
    // ---------------------------------------------------------------------

    private fun handleOpen(frame: JsonObject) {
        val streamId = frame["streamId"]?.jsonPrimitive?.contentOrNull
            ?: return
        val endpoint = frame["endpoint"]?.jsonPrimitive?.contentOrNull
            ?: return
        val payload = frame["payload"]?.jsonObject ?: JsonObject(emptyMap())
        val args = payload["args"]?.jsonObject ?: JsonObject(emptyMap())

        when (endpoint) {
            "\$events" -> {
                streams[streamId] = StreamState(endpoint)
                sendItem(streamId, buildJsonObject {
                    put("type", "ready")
                    put("clientId", UUID.randomUUID().toString())
                    put("host", buildJsonObject {
                        put("home", HOME_PATH)
                    })
                })
            }
            "session/follow" -> {
                val request = args["request"]?.jsonObject
                val sessionId = request?.get("address")?.jsonObject
                    ?.get("sessionId")?.jsonPrimitive?.contentOrNull
                if (sessionId == null) {
                    sendError(
                        streamId,
                        "gateway/arguments-invalid",
                        "missing sessionId in session/follow request"
                    )
                    streams.remove(streamId)
                    return
                }
                streams[streamId] = StreamState(endpoint, sessionId)
                server.registerFollow(sessionId, this, streamId)
                val snapshot = server.buildSnapshot(sessionId)
                sendItem(streamId, snapshot)
            }
            "session/control" -> {
                streams[streamId] = StreamState(endpoint)
                // 最小控制基线：无会话活动时，projections 为空对象。
                sendItem(streamId, buildJsonObject {
                    put("type", "baseline")
                    put("value", buildJsonObject {
                        put("projections", buildJsonObject { })
                    })
                })
            }
            "job/list" -> {
                streams[streamId] = StreamState(endpoint)
                sendItem(streamId, buildJsonObject {
                    put("type", "rows")
                    put("jobs", buildJsonArray { })
                })
            }
            "job/follow" -> {
                streams[streamId] = StreamState(endpoint)
                sendItem(streamId, buildJsonObject {
                    put("type", "opened")
                    put("from", 0)
                })
            }
            else -> {
                sendError(
                    streamId,
                    "gateway/service-unavailable",
                    "unsupported stream endpoint: $endpoint"
                )
                streams.remove(streamId)
            }
        }
    }

    private fun closeStream(streamId: String) {
        val state = streams.remove(streamId) ?: return
        if (state.endpoint == "session/follow" && state.sessionId != null) {
            server.unregisterFollow(state.sessionId, this, streamId)
        }
    }

    // ---------------------------------------------------------------------
    // 发送
    // ---------------------------------------------------------------------

    fun sendItem(streamId: String, value: JsonObject) {
        sendText(buildJsonObject {
            put("type", "item")
            put("streamId", streamId)
            put("value", value)
        })
    }

    private fun sendError(streamId: String, code: String, message: String) {
        sendText(buildJsonObject {
            put("type", "error")
            put("streamId", streamId)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
                put("details", buildJsonObject { })
            })
        })
    }

    private fun sendText(payload: JsonObject) {
        try {
            if (isOpen) {
                send(payload.toString())
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send frame", e)
        }
    }
}