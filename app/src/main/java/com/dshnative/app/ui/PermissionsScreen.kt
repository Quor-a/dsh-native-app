package com.dshnative.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshnative.app.permissions.PermissionItem
import com.dshnative.app.permissions.PermissionManager
import com.dshnative.app.ui.theme.AppColor

/**
 * 权限管理页：查看/授予全部权限（权限插件的前端界面）。
 * 运行时权限直接弹系统授权框；特殊权限跳转系统设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    val grantedCount = remember(refreshTick) {
        PermissionManager.all.count { PermissionManager.isGranted(context, it) }
    }
    val grouped = remember(refreshTick) { PermissionManager.all.groupBy { it.group } }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshTick++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val intent = PermissionManager.buildAppSettingsIntent(context)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "打开系统权限设置")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已授予 ${grantedCount} / ${PermissionManager.all.size} 项权限",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { refreshTick++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新状态")
                        }
                    }
                }
            }
            grouped.forEach { (group, items) ->
                item {
                    Text(
                        group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(items, key = { it.permission ?: it.specialKey ?: it.name }) { item ->
                    PermissionRow(
                        item = item,
                        granted = PermissionManager.isGranted(context, item),
                        onRequestRuntime = {
                            item.permission?.let { requestLauncher.launch(it) }
                        },
                        onOpenSpecial = {
                            when (item.specialKey) {
                                PermissionManager.SPECIAL_SHIZUKU -> {
                                    com.dshnative.app.shizuku.ShizukuManager.requestPermission(context)
                                    refreshTick++
                                }
                                PermissionManager.SPECIAL_ROOT -> {
                                    // Root 无法由应用主动授予，点击后重新检测设备 Root 状态
                                    com.dshnative.app.root.RootManager.refresh()
                                    refreshTick++
                                }
                                else -> {
                                    val intent = PermissionManager.buildSpecialSettingsIntent(
                                        context,
                                        item.specialKey ?: ""
                                    )
                                    if (intent != null) {
                                        try {
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            // 目标应用未安装（如 Shizuku 管理器），忽略并提示刷新状态
                                            refreshTick++
                                        }
                                    } else {
                                        // 无对应设置页（如 Root），点击后刷新状态
                                        refreshTick++
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: PermissionItem,
    granted: Boolean,
    onRequestRuntime: () -> Unit,
    onOpenSpecial: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (granted) {
                Icon(Icons.Filled.Check, contentDescription = "已授予", tint = AppColor.Success)
            } else {
                val isSpecial = item.specialKey != null
                TextButton(
                    onClick = if (isSpecial) onOpenSpecial else onRequestRuntime
                ) {
                    Text(if (isSpecial) "去设置" else "授权")
                }
            }
        }
    }
}