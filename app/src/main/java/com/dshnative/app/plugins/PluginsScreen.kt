package com.dshnative.app.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 插件管理页（对齐 DSH dsh-plugin-manager / dsh-client-ui-plugin-inventory）。
 *
 * 两个 Tab：
 * - 已安装：当前启用插件（内置 + 已安装的可选插件），可停用 / 打开配置面板；
 * - 市场：官方 72 个 @deepseek-ai/dsh-* 插件 + 本地可选插件，内置标「已内置」+ 启用开关，
 *   可选插件标「安装 / 卸载」按钮。
 */
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val registry = remember { PluginRegistry(context) }
    var installed by remember { mutableStateOf(registry.all()) }
    var market by remember { mutableStateOf(registry.marketIndex()) }
    var tab by remember { mutableStateOf(0) } // 0 = 已安装，1 = 市场
    var configTarget by remember { mutableStateOf<DshPlugin?>(null) }

    fun refresh() {
        installed = registry.all()
        market = registry.marketIndex()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶栏
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp).height(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("插件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("一切能力皆插件 · 对齐 DeepSeek Harness", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Tab：已安装 / 市场
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("已安装" to 0, "市场" to 1).forEach { (label, idx) ->
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .background(
                            if (tab == idx) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(999.dp)
                        )
                        .border(
                            1.dp,
                            if (tab == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { tab = idx }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (tab == idx) FontWeight.Bold else FontWeight.Normal,
                        color = if (tab == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (tab == 0) {
            // 已安装 = 启用的内置插件 + 已安装的可选/市场插件
            val enabledIds = installed.filter { it.enabled }.map { it.id }.toSet()
            val enabledMarket = market.filter { it.enabled && !it.builtin }
            val installedList = installed.filter { it.enabled } + enabledMarket.filter { it.id !in enabledIds }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)
            ) {
                items(installedList, key = { it.id }) { p ->
                    PluginCard(
                        p = p,
                        hasConfig = p.configItems.isNotEmpty(),
                        onToggle = { checked ->
                            registry.setBundleEnabled(p.id, checked)
                            refresh()
                        },
                        onConfig = { configTarget = p }
                    )
                }
                if (installedList.isEmpty()) {
                    item { EmptyHint("还没有启用任何插件，去「市场」安装吧") }
                }
            }
        } else {
            // 市场 = 官方 72 插件 + 本地可选插件
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)
            ) {
                item {
                    Text(
                        "共 ${market.size} 个插件（对齐 @deepseek-ai/dsh-* 官方清单）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
                items(market, key = { it.id }) { p ->
                    MarketCard(
                        p = p,
                        onToggle = { checked ->
                            registry.setBundleEnabled(p.id, checked)
                            refresh()
                        },
                        onInstall = {
                            registry.setBundleEnabled(p.id, true)
                            refresh()
                        },
                        onRemove = {
                            registry.removeBundle(p.id)
                            refresh()
                        },
                        onConfig = { configTarget = p }
                    )
                }
            }
        }
    }

    // 配置面板（动态表单）
    configTarget?.let { p ->
        val current = remember(p.id) { registry.config(p.id) }
        PluginConfigDialog(
            plugin = p,
            current = current,
            onDismiss = { configTarget = null },
            onSave = { updated ->
                registry.saveConfig(p.id, updated)
                refresh()
                configTarget = null
            }
        )
    }
}

@Composable
private fun PluginCard(
    p: DshPlugin,
    hasConfig: Boolean,
    onToggle: (Boolean) -> Unit,
    onConfig: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Text(p.category.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("v${p.version}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.optional) {
                        Spacer(Modifier.width(6.dp))
                        Text("可选", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (p.bundleName.isNotBlank() || p.officialId.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        p.bundleName.ifBlank { p.officialId },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.tools.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("工具: ${p.tools.joinToString(", ")}", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary, maxLines = 2)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (hasConfig) {
                IconButton(onClick = onConfig) {
                    Icon(Icons.Filled.Settings, contentDescription = "配置${p.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(
                checked = p.enabled,
                enabled = p.category != PluginCategory.SYSTEM,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun MarketCard(
    p: DshPlugin,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onConfig: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (p.builtin) {
                    Text("已内置", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("可安装", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(
                p.bundleName.ifBlank { p.officialId },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.configItems.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "配置项: ${p.configItems.joinToString(", ") { it.label }}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (p.configItems.isNotEmpty()) {
                    TextButton(onClick = onConfig) {
                        Icon(Icons.Filled.Settings, null, Modifier.width(14.dp).height(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("配置", fontSize = 12.sp)
                    }
                }
                if (p.optional) {
                    // 可选插件：安装/卸载
                    TextButton(onClick = { if (p.enabled) onRemove() else onInstall() }) {
                        if (!p.enabled) {
                            Icon(Icons.Filled.Download, null, Modifier.width(14.dp).height(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("安装", fontSize = 12.sp)
                        } else {
                            Text("卸载", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    // 内置插件：启用开关
                    Switch(
                        checked = p.enabled,
                        enabled = p.category != PluginCategory.SYSTEM,
                        onCheckedChange = onToggle
                    )
                }
            }
        }
    }
}

/** 插件配置面板（动态表单：文本 / 开关 / 枚举） */
@Composable
private fun PluginConfigDialog(
    plugin: DshPlugin,
    current: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit
) {
    val values = remember(current) { mutableStateMapOf<String, String>().apply { putAll(current) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${plugin.name} 配置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                plugin.configItems.forEach { item ->
                    when (item.type) {
                        PluginConfigType.BOOLEAN -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                    if (item.description.isNotBlank()) {
                                        Text(item.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Switch(
                                    checked = values[item.key]?.toBooleanStrictOrNull() ?: (item.defaultValue == "true"),
                                    onCheckedChange = { values[item.key] = it.toString() }
                                )
                            }
                        }
                        PluginConfigType.ENUM -> {
                            Column {
                                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                if (item.description.isNotBlank()) {
                                    Text(item.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val selected = values[item.key] ?: item.defaultValue
                                item.options.forEach { opt ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { values[item.key] = opt }
                                            .background(
                                                if (opt == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            opt,
                                            fontSize = 13.sp,
                                            color = if (opt == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            OutlinedTextField(
                                value = values[item.key] ?: item.defaultValue,
                                onValueChange = { values[item.key] = it },
                                label = { Text(item.label) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(values.toMap()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}