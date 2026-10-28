package com.dshnative.app.termux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 内置 Termux 终端（对应 DSH sidebar-terminal） */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val bg = Color(0xFF0B0E14)
    val fg = Color(0xFFE6EDF3)
    val green = Color(0xFF3FB950)
    val promptColor = Color(0xFF58A6FF)

    var lines by remember { mutableStateOf(listOf("DshNative 内置 Termux 终端")) }
    var input by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }

    // 首次进入：确保 Termux 已解压
    LaunchedEffect(Unit) {
        if (!TermuxManager.isExtracted()) {
            extracting = true
            lines = lines + "首次使用：正在解压完整 Termux 环境（约 50MB，请稍候）..."
            val ok = withContext(Dispatchers.IO) {
                TermuxManager.ensureExtracted(context)
            }
            extracting = false
            ready = ok
            lines = if (ok) {
                lines + "Termux 就绪。试试：pwd / ls / echo hello / whoami"
            } else {
                lines + "Termux 解压失败：" + (TermuxManager.lastError ?: "请检查存储空间。")
            }
        } else {
            ready = true
            lines = lines + "Termux 就绪。试试：pwd / ls / echo hello / whoami"
        }
    }

    fun runCommand(cmd: String) {
        if (cmd.isBlank() || busy || !ready) return
        busy = true
        lines = lines + "$ $cmd"
        input = ""
        scope.launch(Dispatchers.IO) {
            val result = TermuxManager.exec(cmd)
            val out = try {
                val obj = Json.parseToJsonElement(result).jsonObject
                val stdout = obj["stdout"]?.jsonPrimitive?.contentOrNull ?: ""
                val stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull ?: ""
                val err = obj["error"]?.jsonPrimitive?.contentOrNull
                if (err != null) "错误: $err"
                else (stdout + stderr).ifBlank { "(无输出)" }
            } catch (e: Exception) {
                "解析失败: ${e.message}"
            }
            lines = lines + out.trim()
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        // 顶栏
        Surface(color = Color(0xFF161B22)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        tint = fg)
                }
                Icon(Icons.Filled.Terminal, contentDescription = null, tint = green,
                    modifier = Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("终端", color = fg, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ready) "● 就绪" else if (extracting) "解压中…" else "未就绪",
                    color = if (ready) green else Color(0xFFE3B341),
                    fontSize = 12.sp
                )
            }
        }

        // 输出区
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            lines.forEach { line ->
                val isPrompt = line.startsWith("$ ")
                Text(
                    text = line,
                    color = if (isPrompt) promptColor else fg,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (busy) {
                Text("…", color = green, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // 输入区
        Surface(color = Color(0xFF161B22)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$", color = green, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = fg, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(green),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { runCommand(input) })
                ) { inner -> inner() }
                IconButton(
                    onClick = { runCommand(input) },
                    enabled = ready && !busy
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "执行", tint = green)
                }
            }
        }
    }
}