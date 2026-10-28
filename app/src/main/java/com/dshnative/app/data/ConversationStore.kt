package com.dshnative.app.data

import android.content.Context
import com.dshnative.app.model.Conversation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 会话持久化（SharedPreferences + JSON） */
class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("dshnative_conversations", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadAll(): List<Conversation> {
        val raw = prefs.getString("conversations", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Conversation>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(list: List<Conversation>) {
        prefs.edit().putString("conversations", json.encodeToString(list)).apply()
    }
}