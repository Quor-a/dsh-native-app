package com.dshnative.app.ui

import android.content.Context
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshnative.app.config.AppConfig
import com.dshnative.app.model.ChatMessage
import com.dshnative.app.model.Conversation
import com.dshnative.app.plugins.PluginsScreen
import com.dshnative.app.termux.TerminalScreen
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

val LocalMarkwon = staticCompositionLocalOf<Markwon> {
    error("Markwon not provided")
}

// 内容最大宽度（对应 DSH --dsh-chat-content-width: min(100%-32px, 920px)）
private val ChatContentMaxWidth = 920.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val config = remember { AppConfig(context) }
    val markwon = remember { Markwon.builder(context).build() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    val busy = viewModel.busy.value
    val current = viewModel.currentConversation

    if (showPlugins) {
        PluginsScreen(onBack = { showPlugins = false })
    } else if (showTerminal) {
        TerminalScreen(onBack = { showTerminal = false })
    } else {
        CompositionLocalProvider(LocalMarkwon provides markwon) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DshSidebar(
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
                    onRename = { id, title -> viewModel.renameConversation(id, title) },
                    onPlugins = { showPlugins = true },
                    onTerminal = { showTerminal = true }
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 顶部：极简导航条（手机端保留菜单入口）
                DshTopBar(
                    title = current?.title ?: "DeepSeek Harness",
                    onMenu = { scope.launch { drawerState.open() } },
                    onNew = { viewModel.newConversation() },
                    onSettings = { showSettings = true }
                )
                DshMessageList(viewModel = viewModel)
                DshComposer(
                    model = config.model,
                    busy = busy,
                    onModelClick = { showModelPicker = true },
                    onSend = { viewModel.send(it) }
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(config = config, onDismiss = { showSettings = false })
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = config.models,
            current = config.model,
            onSelect = {
                config.model = it
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
    }
}

/** 顶部导航条：菜单 / 会话名 / 新建 / 设置 */
@Composable
fun DshTopBar(title: String, onMenu: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "会话列表",
                tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onNew) {
            Icon(Icons.Filled.Add, contentDescription = "新建会话",
                tint = MaterialTheme.colorScheme.onBackground)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/** 消息流：助手消息无气泡，用户消息深灰气泡（DSH 风格） */
@Composable
fun DshMessageList(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val messages = viewModel.currentConversation?.messages ?: emptyList()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (messages.isEmpty()) {
            item {
                DshEmptyState()
            }
        }
        items(messages) { msg ->
            DshMessageBubble(msg)
        }
        val toolStatus = viewModel.toolStatus.value
        if (toolStatus.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(
                        text = "⚙  $toolStatus",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** 空会话欢迎页（对应 DSH 的 "探索未至之境" hero） */
@Composable
fun DshEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "探索未至之境",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "描述你想要构建的内容, / 调用指令, @ 文件或对话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DshMessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .widthIn(max = ChatContentMaxWidth),
        horizontalArrangement = Arrangement.Start
    ) {
        if (isUser) {
            // 用户消息：深灰气泡，20dp 圆角（DSH Sixlwa_bubble）
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 480.dp)
            ) {
                Text(
                    text = msg.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else if (msg.content.isEmpty()) {
            // 助手占位：闪烁光标
            Text(
                text = "▍",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            // 助手消息：无气泡（透明背景），Markdown 渲染
            val markwon = LocalMarkwon.current
            val contentColor = MaterialTheme.colorScheme.onBackground
            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        setTextSize(16f)
                        setTextColor(contentColor.toArgb())
                        setLineSpacing(2f, 1.0f)
                        setPadding(0, 4, 0, 4)
                        setTextIsSelectable(true)
                    }
                },
                update = { tv ->
                    tv.setTextColor(contentColor.toArgb())
                    markwon.setMarkdown(tv, msg.content)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 底部输入卡（DSH composer）：模型标签 + 输入框 + 胶囊发送按钮 */
@Composable
fun DshComposer(model: String, busy: Boolean, onModelClick: () -> Unit, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                // 模型标签行（对应 "工作区内修改 u2-flash"）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
                            .clickable(onClick = onModelClick)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = model,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "工作区内修改",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    text = "描述你想要构建的内容…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 15.sp
                                )
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                    CircleShape
                                )
                                .clickable(enabled = input.isNotBlank()) {
                                    onSend(input)
                                    input = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "发送",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 侧栏（DSH sidebar）：品牌 + 新会话按钮 + 工作区列表 + 用户 */
@Composable
fun DshSidebar(
    conversations: List<Conversation>,
    currentId: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPlugins: () -> Unit,
    onTerminal: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(288.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        // 品牌行：DeepSeek Harness + 预览版 badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("鱼", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "DeepSeek Harness",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("预览版", color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(14.dp))

        // 新会话按钮（DSH hHd-Xa_newSession：边框 + 12dp 圆角）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .clickable { onNew() }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新会话", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))

        // 插件入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable { onPlugins() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("插件", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("管理", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))

        // 终端入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable { onTerminal() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("终端", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("Termux", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))

        // 工作区节标题
        Text(
            text = "工作区",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(4.dp))

        // 会话列表
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { conv ->
                val selected = conv.id == currentId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) MaterialTheme.colorScheme.outlineVariant
                            else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(conv.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conv.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).clickable { renameTarget = conv.id }
                    )
                    IconButton(
                        onClick = { onDelete(conv.id) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // 底部用户
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text("agentuser", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
                text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        onRename(conv.id, title.trim().ifEmpty { "新对话" })
                        renameTarget = null
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
            )
        }
    }
}

/** 设置对话框 */
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
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 模型选择对话框（DSH 模型选择器） */
@Composable
fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                models.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (m == current) MaterialTheme.colorScheme.outlineVariant
                                else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelect(m) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = m,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (m == current) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.weight(1f))
                        if (m == current) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}