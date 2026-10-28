package com.dshnative.app.service

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 执行 shell 命令（无需 root）。
 * 用户需安装 Shizuku 应用并授权本应用。
 */
object ShizukuShell {

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** 执行命令，返回 JSON 字符串结果 */
    fun exec(command: String): String {
        if (!isAvailable()) {
            return """{"error":"Shizuku 未运行。请安装 Shizuku（https://shizuku.rikka.app）并启动服务"}"""
        }
        if (!isGranted()) {
            return """{"error":"Shizuku 未授权。请在 Shizuku 应用里为本应用授予权限"}"""
        }
        return try {
            // newProcess 是隐藏 API，通过反射调用（Shizuku 13.x 运行时可用）
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("/system/bin/sh", "-c", command), null, null) as rikka.shizuku.ShizukuRemoteProcess
            process.waitFor()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            """{"exit":${process.exitValue()},"stdout":${JsonEscape(out.take(4000))},"stderr":${JsonEscape(err.take(1000))}}"""
        } catch (e: Exception) {
            """{"error":"Shizuku 执行失败: ${e.message}"}"""
        }
    }

    private fun JsonEscape(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}