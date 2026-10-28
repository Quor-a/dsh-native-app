package com.dshnative.app.ui

import android.content.Context
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshnative.app.config.AppConfig
import com.dshnative.app.model.ChatMessage
import com.dshnative.app.model.Conversation
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

val LocalMarkwon = staticCompositionLocalOf<Markwon> {
    error("Markwon not provided")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val config = remember { AppConfig(context) }
    val markwon = remember { Markwon.builder(context).build() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val busy = viewModel.busy.value
    val current = viewModel.currentConversation

    CompositionLocalProvider(LocalMarkwon provides markwon) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    ConversationDrawer(
                        conversations = viewModel.conversations,
                        currentId = viewModel.currentId,
                        onSelect = {
                            viewModel.switchConversation(it)
                            scope.launch { drawerState.close() }
                        },
                        onNew = {
                            viewModel.newConversation()
                            scope.launch { drawerState.close() }
                        },
                        onDelete = { viewModel.deleteConversation(it) },
                        onRename = { id, title -> viewModel.renameConversation(id, title) }
                    )
                }
            }
        ) {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                ChatTopBar(
                    title = current?.title ?: "DshNative",
                    onMenu = { scope.launch { drawerState.open() } },
                    onNew = { viewModel.newConversation() },
                    onSettings = { showSettings = true }
                )

                MessageList(viewModel = viewModel, modifier = Modifier.weight(1f))

                ChatInputBar(busy = busy) {
                    viewModel.send(it)
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(config = config, onDismiss = { showSettings = false })
    }
}

@Composable
fun ChatTopBar(title: String, onMenu: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "会话列表", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun MessageList(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val messages = viewModel.currentConversation?.messages ?: emptyList()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        if (messages.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "我是安卓原生 AI 助手\n可以操控手机、执行命令、截图分析",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                }
            }
        }
        items(messages) { msg ->
            ChatBubble(msg)
        }
    }
}

@Composable
fun ChatInputBar(busy: Boolean, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入消息…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
                } else {
                    IconButton(
                        onClick = {
                            onSend(input)
                            input = ""
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            if (isUser || msg.content.isEmpty()) {
                Text(
                    text = msg.content,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val markwon = LocalMarkwon.current
                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextSize(14f)
                            setTextColor(android.graphics.Color.parseColor("#E6EDF3"))
                            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                            setTextIsSelectable(true)
                        }
                    },
                    update = { tv -> markwon.setMarkdown(tv, msg.content) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    currentId: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxHeight().width(300.dp).padding(16.dp)
    ) {
        Text("会话", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建会话")
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { conv ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(conv.id) }
                        .background(
                            if (conv.id == currentId) MaterialTheme.colorScheme.surfaceVariant
                            else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conv.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).clickable { renameTarget = conv.id }
                    )
                    IconButton(
                        onClick = { onDelete(conv.id) },
                        modifier = Modifier.width(28.dp).height(28.dp)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp).height(16.dp))
                    }
                }
            }
        }
    }

    val target = renameTarget
    if (target != null) {
        val conv = conversations.firstOrNull { it.id == target }
        if (conv != null) {
            var title by remember { mutableStateOf(conv.title) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(onClick = {
                        onRename(conv.id, title.trim().ifEmpty { "新对话" })
                        renameTarget = null
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
fun SettingsDialog(config: AppConfig, onDismiss: () -> Unit) {
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                config.apiKey = apiKey.trim()
                config.baseUrl = baseUrl.trim()
                config.model = model.trim()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}