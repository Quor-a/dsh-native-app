package com.dshnative.app.workspace

import android.content.Context
import java.io.File

/**
 * 工作区管理（对应 DSH 的 workspace 概念）。
 *
 * 工作区目录统一放在应用私有目录 `filesDir/workspace/<工作区名>` 下，
 * 与 [WorkspaceScreen] 浏览的文件根目录同源。本类负责：
 * - 列出已有工作区
 * - 新建工作区（自动防止路径穿越与同名覆盖）
 * - 记住当前选中的工作区（SharedPreferences 持久化）
 */
class WorkspaceManager(context: Context) {

    private val prefs = context.getSharedPreferences("dshnative_workspace", Context.MODE_PRIVATE)
    private val rootDir = File(context.filesDir, "workspace")

    /** 当前工作区名称；未选择时返回 null */
    var currentWorkspace: String?
        get() = prefs.getString(KEY_CURRENT, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_CURRENT, value).apply()
        }

    /** 当前工作区的绝对路径；未选择或目录不存在时返回 null */
    fun currentWorkspacePath(): String? {
        val name = currentWorkspace ?: return null
        val dir = File(rootDir, name)
        return if (dir.isDirectory) dir.absolutePath else null
    }

    /** 已有工作区名称列表（按名称排序） */
    fun listWorkspaces(): List<String> {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.map { it.name }.sorted()
    }

    /**
     * 新建工作区。
     * 名称空白、为 `.` / `..`、包含路径分隔符时返回 false；
     * 已存在同名目录时也返回 false（调用方可转为“直接选中”）。
     */
    fun createWorkspace(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "." || trimmed == "..") return false
        if (trimmed.contains('/') || trimmed.contains('\\')) return false
        val dir = File(rootDir, trimmed)
        return if (dir.exists()) false else dir.mkdirs()
    }

    /** 选中某个工作区作为当前工作区（目录必须已存在） */
    fun selectWorkspace(name: String) {
        val dir = File(rootDir, name)
        if (dir.isDirectory) {
            currentWorkspace = name
        }
    }

    companion object {
        private const val KEY_CURRENT = "current_workspace"
    }
}