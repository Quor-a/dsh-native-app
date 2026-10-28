package com.dshnative.app.workspace

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区文件浏览器（对应 DSH dsh-client-ui-sidebar-files + dsh-api-workspace-files）。
 * 浏览应用私有目录与公共存储，支持打开/编辑文本文件、新建文件/目录。
 */
@Composable
fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val privateRoot = context.filesDir.absolutePath
    val downloadRoot = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath

    var currentPath by remember { mutableStateOf(privateRoot) }
    var entries by remember { mutableStateOf(listFiles(currentPath)) }
    var openFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewDir by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun refresh() {
        entries = listFiles(currentPath)
    }

    fun enter(path: String) {
        currentPath = path
        refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶栏
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text("工作区", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(currentPath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { showNewFile = true }) {
                    Icon(Icons.Filled.NoteAdd, "新建文件", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showNewDir = true }) {
                    Icon(Icons.Filled.CreateNewFolder, "新建目录", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 根目录快捷切换
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkspaceChip("App 私有目录", currentPath == privateRoot) { enter(privateRoot) }
            WorkspaceChip("下载", currentPath == downloadRoot) { enter(downloadRoot) }
        }

        // 返回上级
        if (currentPath != "/" && currentPath != privateRoot && currentPath != downloadRoot) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        val parent = File(currentPath).parentFile
                        if (parent != null) enter(parent.absolutePath)
                    }
                    .padding(horizontal = 8.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⬆ 返回上级", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }

        // 文件列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)
        ) {
            items(entries, key = { it.path }) { f ->
                val isDir = f.isDirectory
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Row(
                        Modifier.clickable {
                            if (isDir) enter(f.absolutePath) else {
                                val text = try { f.readText().take(200_000) } catch (_: Exception) { "" }
                                openFile = f
                                fileContent = text
                            }
                        }
                            .padding(horizontal = 8.dp)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            null,
                            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(18.dp).height(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Text(
                                if (isDir) "目录" else "${formatSize(f.length())} · ${formatTime(f.lastModified())}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                item { Text("（空目录）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
        }
    }

    // 新建文件对话框
    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("新建文件") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("文件名，如 notes.md") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        val f = File(currentPath, newName.trim())
                        if (!f.exists()) { try { f.createNewFile() } catch (_: Exception) {} }
                        newName = ""
                        showNewFile = false
                        refresh()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("取消") } }
        )
    }

    // 新建目录对话框
    if (showNewDir) {
        AlertDialog(
            onDismissRequest = { showNewDir = false },
            title = { Text("新建目录") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("目录名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        val d = File(currentPath, newName.trim())
                        if (!d.exists()) { try { d.mkdirs() } catch (_: Exception) {} }
                        newName = ""
                        showNewDir = false
                        refresh()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewDir = false }) { Text("取消") } }
        )
    }

    // 文件编辑对话框
    openFile?.let { file ->
        AlertDialog(
            onDismissRequest = { openFile = null },
            title = { Text(file.name, maxLines = 1) },
            text = {
                OutlinedTextField(
                    value = fileContent,
                    onValueChange = { fileContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try { file.writeText(fileContent) } catch (_: Exception) {}
                    openFile = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { openFile = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun WorkspaceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

private fun listFiles(path: String): List<File> {
    val dir = File(path) ?: return emptyList()
    val files = dir.listFiles() ?: return emptyList()
    return files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
}

private fun formatSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / 1048576.0)
    size >= 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}

private fun formatTime(t: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))