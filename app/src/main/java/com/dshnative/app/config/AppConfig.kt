package com.dshnative.app.config

import android.content.Context

/** 应用配置（SharedPreferences 存储） */
class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("dshnative", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("api_key", value).apply()
        }

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(value) {
            prefs.edit().putString("base_url", value.trimEnd('/')).apply()
        }

    var model: String
        get() = prefs.getString("model", "deepseek-chat") ?: "deepseek-chat"
        set(value) {
            prefs.edit().putString("model", value).apply()
        }
}