package com.dshnative.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshnative.app.dshweb.DshWebServer
import com.dshnative.app.ui.ChatScreen
import com.dshnative.app.ui.theme.DshNativeTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val dshWebServer = DshWebServer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局崩溃捕获：将堆栈写入 filesDir/crash.log，下次启动显示
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = File(filesDir, "crash.log")
                f.writeText("thread=${thread.name}\n" + Log.getStackTraceString(throwable))
            } catch (_: Exception) {
            }
            // 不调用默认 handler（避免系统弹窗），进程随后由系统回收
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        // 启动本地 DSH Web 协议层服务器（127.0.0.1:8756），供 WebView 加载 Web UI。
        dshWebServer.start(applicationContext)

        setContent {
            DshNativeTheme {
                val crash = remember { readCrashLog() }
                if (crash != null) {
                    CrashReportDialog(
                        detail = crash,
                        onCopy = { copyCrashLog(crash) },
                        onDismiss = { clearCrashLog() }
                    )
                }
                ChatScreen()
            }
        }
    }

    override fun onDestroy() {
        dshWebServer.stopServer()
        super.onDestroy()
    }

    private fun readCrashLog(): String? {
        return try {
            val f = File(filesDir, "crash.log")
            if (f.exists()) f.readText().take(6000) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun clearCrashLog() {
        try {
            File(filesDir, "crash.log").delete()
        } catch (_: Exception) {
        }
    }

    private fun copyCrashLog(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", text))
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun CrashReportDialog(detail: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上次运行发生错误") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "请点击下方“复制错误报告”并发给开发者，这能帮助定位闪退原因：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text("复制错误报告") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}