package com.dshnative.app.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

/** 插件管理页（对应 DSH dsh-plugin-manager / dsh-client-ui-plugin-market） */
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val registry = remember { PluginRegistry(context) }
    var plugins by remember { mutableStateOf(registry.all()) }
    var filter by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶栏
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface)
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
                    Text("一切能力皆插件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 分类过滤（全部/工具/功能/界面/系统）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { FilterChip("全部", filter == null) { filter = null } }
            PluginCategory.entries.forEach { cat ->
                item { FilterChip(cat.label, filter == cat.name) { filter = cat.name } }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)
        ) {
            items(plugins.filter { filter == null || it.category.name == filter }, key = { it.id }) { p ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(6.dp))
                                Text(p.category.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(6.dp))
                                Text("v${p.version}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (p.builtin) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("内置", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                if (p.optional) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("可安装", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (p.tools.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "工具: ${p.tools.joinToString(", ")}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            if (p.dependencies.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "依赖: ${p.dependencies.joinToString(", ")}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = p.enabled,
                            enabled = p.category != PluginCategory.SYSTEM,
                            onCheckedChange = { checked ->
                                registry.setEnabled(p.id, checked)
                                plugins = registry.all()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label == "可安装") {
            Icon(Icons.Filled.InstallDesktop, null, Modifier.width(12.dp).height(12.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}