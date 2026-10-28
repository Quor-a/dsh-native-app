package com.dshnative.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshnative.app.model.ApiProviderConfigs
import com.dshnative.app.model.ApiProviderType
import com.dshnative.app.model.QuroCustomProvider
import com.dshnative.app.model.QuroCustomProviderRepository
import com.dshnative.app.model.QuroModelConfig
import com.dshnative.app.model.QuroModelConfigRepository
import com.dshnative.app.model.QuroModelInfo
import com.dshnative.app.model.QuroModelListFetcher
import com.dshnative.app.model.QuroModelListResult
import com.dshnative.app.model.QuroSavedProfileRepository
import com.dshnative.app.model.toProfile
import kotlinx.coroutines.launch

/** ZorvAI 模型配置 ViewModel（移植自 QuroModelConfigViewModel） */
class ModelConfigViewModel(private val context: Context) : ViewModel() {
    val repo = QuroModelConfigRepository(context.applicationContext)
    val profilesRepo = QuroSavedProfileRepository(context.applicationContext)
    val customRepo = QuroCustomProviderRepository(context.applicationContext)
    private val fetcher = QuroModelListFetcher()

    var cfg by mutableStateOf(repo.load())
    var isFetchingModels by mutableStateOf(false)
    var modelList by mutableStateOf<QuroModelListResult?>(null)
    var savedProfiles by mutableStateOf(profilesRepo.loadAll())
    var customProviders by mutableStateOf(customRepo.loadAll())
    var showProfileNameDialog by mutableStateOf(false)

    fun update(block: QuroModelConfig.() -> QuroModelConfig) {
        val old = cfg
        val new = cfg.block()
        // 自动识别云服务商（基础 URL 变化时）
        if (old.baseUrl != new.baseUrl && new.baseUrl.isNotBlank()) {
            val detected = ApiProviderConfigs.detectProviderFromUrl(new.baseUrl)
            if (detected.name != new.provider) cfg = new.copy(provider = detected.name)
            else cfg = new
        } else {
            cfg = new
        }
        repo.save(cfg)  // 每次编辑即落盘
    }

    fun refresh() {
        savedProfiles = profilesRepo.loadAll()
        customProviders = customRepo.loadAll()
    }

    fun fetchModels() {
        val c = cfg
        isFetchingModels = true
        modelList = null
        viewModelScope.launch {
            val res = try {
                fetcher.fetch(c.baseUrl, c.apiKey)
            } catch (e: Exception) {
                QuroModelListResult.Error(e.message ?: "拉取失败")
            }
            modelList = res
            repo.saveModelListCache(c.baseUrl, res)
            isFetchingModels = false
        }
    }

    fun selectModel(id: String) {
        update { copy(model = id) }
        modelList = null
    }

    fun saveProfile(name: String) {
        profilesRepo.save(cfg.toProfile(name))
        savedProfiles = profilesRepo.loadAll()
    }

    fun applyProfile(profile: com.dshnative.app.model.QuroSavedProfile) {
        profilesRepo.applyToConfig(profile, repo)
        cfg = repo.load()
    }

    fun deleteProfile(id: String) {
        profilesRepo.delete(id)
        savedProfiles = profilesRepo.loadAll()
    }

    fun upsertCustom(p: QuroCustomProvider) {
        customRepo.upsert(p)
        customProviders = customRepo.loadAll()
    }

    fun deleteCustom(id: String) {
        customRepo.delete(id)
        customProviders = customRepo.loadAll()
    }
}

/** 模型配置页（移植 ZorvAI QuroModelConfigScreen 核心功能） */
@Composable
fun ModelConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ModelConfigViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ModelConfigViewModel(context) }
        }
    )
    var showApiKey by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var showAddCustom by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }

    val cfg = vm.cfg
    val providerConfig = ApiProviderConfigs.configsFor(cfg.provider)

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
                Column(Modifier.padding(start = 8.dp)) {
                    Text("设置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("模型配置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前配置摘要
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("当前接入点", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${cfg.provider} · ${cfg.model.ifBlank { "未选模型" }}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (cfg.baseUrl.isNotBlank()) {
                            Text(cfg.baseUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }

            // 服务商选择
            item {
                Text("服务商", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ApiProviderConfigs.allConfigs(), key = { it.providerType.name }) { p ->
                        val selected = p.providerType.name == cfg.provider
                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .clickable {
                                    vm.update { copy(provider = p.providerType.name, baseUrl = p.defaultApiEndpoint, model = p.defaultModelName) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
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
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(p.providerType.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            // 自定义服务商快捷入口
            item {
                TextButton(onClick = { showAddCustom = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.width(14.dp).height(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加自定义服务商", fontSize = 12.sp)
                }
            }

            // 基本字段
            item {
                QuroField("Base URL", cfg.baseUrl, { vm.update { copy(baseUrl = it) } }, "https://api.deepseek.com/v1")
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cfg.apiKey,
                        onValueChange = { vm.update { copy(apiKey = it) } },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            "显示/隐藏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 模型名 + 拉取模型列表
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cfg.model,
                        onValueChange = { vm.update { copy(model = it) } },
                        label = { Text("模型") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { vm.fetchModels() }, enabled = !vm.isFetchingModels) {
                        Icon(Icons.Filled.Refresh, null, Modifier.width(14.dp).height(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (vm.isFetchingModels) "拉取中…" else "拉取列表", fontSize = 12.sp)
                    }
                }
            }

            // 高级设置
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("高级设置", style = MaterialTheme.typography.titleSmall)
                        SettingsSlider("Temperature", cfg.temperature, onChange = { v -> vm.update { copy(temperature = v) } })
                        SettingsSlider("Max Tokens", cfg.maxTokens.toFloat(), { v -> vm.update { copy(maxTokens = v.toInt()) } }, 256f, 131072f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用工具调用", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = cfg.enableTools, onCheckedChange = { vm.update { copy(enableTools = it) } })
                        }
                    }
                }
            }

            // 保存预设
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已保存预设", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        profileName = ""
                        vm.showProfileNameDialog = true
                    }) {
                        Icon(Icons.Filled.Add, null, Modifier.width(14.dp).height(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存当前配置", fontSize = 12.sp)
                    }
                }
                if (vm.savedProfiles.isEmpty()) {
                    Text("还没有预设。保存后可一键切换模型接入点。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.savedProfiles.forEach { p ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Row(
                            Modifier.clickable { vm.applyProfile(p) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "${p.provider} · ${p.model.ifBlank { "未设置" }}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(p.baseUrl, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = { vm.deleteProfile(p.id) }) {
                                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // 自定义服务商列表
            item {
                Text("自定义服务商", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (vm.customProviders.isEmpty()) {
                    Text("没有自定义服务商。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.customProviders.forEach { cp ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cp.name, style = MaterialTheme.typography.bodyMedium)
                                Text(cp.baseUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            TextButton(onClick = {
                                vm.update {
                                    copy(
                                        provider = "OTHER",
                                        baseUrl = cp.baseUrl,
                                        model = cp.defaultModel,
                                        customProviderName = cp.name
                                    )
                                }
                            }) { Text("使用", fontSize = 12.sp) }
                            IconButton(onClick = { vm.deleteCustom(cp.id) }) {
                                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // 模型列表对话框
    vm.modelList?.let { result ->
        when (result) {
            is QuroModelListResult.Success -> {
                AlertDialog(
                    onDismissRequest = { vm.modelList = null },
                    title = { Text("选择模型（${result.models.size}）") },
                    text = {
                        LazyColumn {
                            items(result.models, key = { it.id }) { m ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { vm.selectModel(m.id) }
                                        .padding(horizontal = 16.dp)
                                        .heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(m.id, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                    if (m.contextLength > 0) {
                                        Text("${m.contextLength / 1000}K", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (result.models.isEmpty()) {
                                item { Text("未获取到模型（可能不支持 /models 接口）", fontSize = 12.sp) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { vm.modelList = null }) { Text("关闭") } }
                )
            }
            is QuroModelListResult.Error -> {
                AlertDialog(
                    onDismissRequest = { vm.modelList = null },
                    title = { Text("拉取失败") },
                    text = { Text(result.message, fontSize = 13.sp) },
                    confirmButton = { TextButton(onClick = { vm.modelList = null }) { Text("关闭") } }
                )
            }
        }
    }

    // 保存预设对话框
    if (vm.showProfileNameDialog) {
        AlertDialog(
            onDismissRequest = { vm.showProfileNameDialog = false },
            title = { Text("保存当前配置为预设") },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (profileName.isNotBlank()) {
                        vm.saveProfile(profileName.trim())
                        profileName = ""
                        vm.showProfileNameDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { vm.showProfileNameDialog = false }) { Text("取消") } }
        )
    }

    // 添加自定义服务商对话框
    if (showAddCustom) {
        AlertDialog(
            onDismissRequest = { showAddCustom = false },
            title = { Text("添加自定义服务商") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text("默认模型（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (customName.isNotBlank() && customUrl.isNotBlank()) {
                        vm.upsertCustom(
                            QuroCustomProvider(
                                id = java.util.UUID.randomUUID().toString(),
                                name = customName.trim(),
                                baseUrl = customUrl.trim().trimEnd('/'),
                                defaultModel = customModel.trim(),
                                requiresApiKey = true
                            )
                        )
                        customName = ""; customUrl = ""; customModel = ""
                        showAddCustom = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAddCustom = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun QuroField(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsSlider(label: String, value: Float, onChange: (Float) -> Unit, min: Float = 0f, max: Float = 2f) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}