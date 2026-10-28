package com.dshnative.app.ui

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.dshnative.app.model.QuroModelListFetcher
import com.dshnative.app.model.QuroModelListResult
import com.dshnative.app.model.QuroSavedProfileRepository
import com.dshnative.app.model.toProfile
import com.dshnative.app.ui.theme.AppRadius
import com.dshnative.app.ui.theme.AppSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== 模型下拉选项（缓存列表 + 兜底模型） =====

enum class ModelOptionSource { CACHE, FALLBACK }

data class ModelOption(
    val id: String,
    val contextLength: Int = 0,
    val source: ModelOptionSource = ModelOptionSource.FALLBACK,
)

/** 常用模型兜底：未拉取到 /models 列表时展示，保证用户总能选到模型。 */
private val COMMON_MODELS = listOf(
    "gpt-4o-mini",
    "gpt-4o",
    "gpt-4.1",
    "o3-mini",
    "deepseek-chat",
    "deepseek-reasoner",
    "claude-3-5-sonnet-20241022",
    "claude-3-5-haiku-20241022",
    "gemini-2.0-flash",
    "gemini-1.5-pro",
    "qwen-max",
    "qwen-plus",
    "glm-4.5",
    "moonshot-v1-8k",
    "ernie-4.0-8k",
    "llama-3.3-70b-versatile",
    "mixtral-8x7b-instruct",
)

/** 模型配置 ViewModel：持有配置、模型列表缓存、预设与自定义服务商。 */
class ModelConfigViewModel(private val context: Context) : ViewModel() {
    val repo = QuroModelConfigRepository(context.applicationContext)
    val profilesRepo = QuroSavedProfileRepository(context.applicationContext)
    val customRepo = QuroCustomProviderRepository(context.applicationContext)
    private val fetcher = QuroModelListFetcher()

    var isInitializing by mutableStateOf(true)
    var cfg by mutableStateOf(QuroModelConfig())
    var isFetchingModels by mutableStateOf(false)
    var modelList by mutableStateOf<QuroModelListResult?>(null)
    var modelsCache by mutableStateOf<QuroModelListResult?>(null)
    var savedProfiles by mutableStateOf(profilesRepo.loadAll())
    var customProviders by mutableStateOf(customRepo.loadAll())
    var showProfileNameDialog by mutableStateOf(false)
    var showModelPicker by mutableStateOf(false)

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.load() }
            cfg = loaded
            modelsCache = repo.loadModelListCache(loaded.baseUrl)
            isInitializing = false
        }
    }

    fun update(block: QuroModelConfig.() -> QuroModelConfig) {
        val old = cfg
        val new = cfg.block()
        // 仅当用户没有显式切换服务商、只改了 Base URL 时，才按 URL 自动识别服务商。
        val resolved = if (old.baseUrl != new.baseUrl && new.baseUrl.isNotBlank() && new.provider == old.provider) {
            val detected = ApiProviderConfigs.detectProviderFromUrl(new.baseUrl)
            if (detected.name != new.provider) new.copy(provider = detected.name) else new
        } else {
            new
        }
        cfg = resolved
        repo.save(cfg) // 每次编辑即落盘
        if (old.baseUrl != resolved.baseUrl) {
            modelsCache = repo.loadModelListCache(resolved.baseUrl)
        }
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
            modelsCache = res
            isFetchingModels = false
        }
    }

    fun selectModel(id: String) {
        update { copy(model = id) }
        modelList = null
        showModelPicker = false
    }

    /** 组装模型选择列表：缓存中的真实模型 + 服务商默认模型 + 常用模型兜底。 */
    fun modelOptions(): List<ModelOption> {
        val cached = (modelsCache as? QuroModelListResult.Success)?.models.orEmpty()
        val providerType = ApiProviderType.fromProviderTypeId(cfg.provider) ?: ApiProviderType.OPENAI_GENERIC
        val defaultModel = ApiProviderConfigs.getDefaultModelName(providerType)
        val seen = cached.mapTo(mutableSetOf()) { it.id }
        val options = cached.mapTo(mutableListOf()) { ModelOption(it.id, it.contextLength, ModelOptionSource.CACHE) }
        val fallback = linkedSetOf<String>()
        if (defaultModel.isNotBlank()) fallback.add(defaultModel)
        fallback.addAll(COMMON_MODELS)
        fallback.forEach { id ->
            if (seen.add(id)) options += ModelOption(id, 0, ModelOptionSource.FALLBACK)
        }
        return options
    }

    /** 保存前兜底：模型名为空时填服务商默认模型，默认也为空则填 deepseek-chat。 */
    fun saveWithModelFallback() {
        val providerType = ApiProviderType.fromProviderTypeId(cfg.provider) ?: ApiProviderType.OPENAI_GENERIC
        val fallback = ApiProviderConfigs.getDefaultModelName(providerType).ifBlank { "deepseek-chat" }
        val model = cfg.model.trim().ifBlank { fallback }
        cfg = cfg.copy(model = model)
        repo.save(cfg)
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

/** 模型配置页：服务商 / 模型 / API Key / Base URL / 参数 / 预设。 */
@Composable
fun ModelConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ModelConfigViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ModelConfigViewModel(context) }
        }
    )

    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var saveAttempted by rememberSaveable { mutableStateOf(false) }
    var profileName by rememberSaveable { mutableStateOf("") }
    var showAddCustom by rememberSaveable { mutableStateOf(false) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var customModel by rememberSaveable { mutableStateOf("") }

    val cfg = vm.cfg
    val endpointOptions = ApiProviderConfigs.getEndpointOptions(
        ApiProviderType.fromProviderTypeId(cfg.provider) ?: ApiProviderType.OPENAI_GENERIC
    ).orEmpty()
    val requiresKey = ApiProviderConfigs.requiresApiKey(cfg.provider, cfg.baseUrl)
    val apiKeyError = saveAttempted && requiresKey && cfg.apiKey.isBlank()

    fun handleSave() {
        saveAttempted = true
        if (requiresKey && cfg.apiKey.isBlank()) return
        vm.saveWithModelFallback()
        Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
        onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.Xs, vertical = AppSpace.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Column(Modifier.padding(start = AppSpace.Sm)) {
                        Text("模型配置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text("接入点与参数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = { handleSave() },
                    enabled = !vm.isInitializing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.Md, vertical = AppSpace.Sm)
                        .height(48.dp),
                    shape = RoundedCornerShape(AppRadius.Md),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "保存配置", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存配置")
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (vm.isInitializing) {
                LoadingState(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AppSpace.Md, vertical = AppSpace.Sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpace.Md),
                ) {
                    item(key = "provider-title") { SectionTitle("服务商") }
                    item(key = "provider-row") {
                        ProviderSelectorRow(
                            builtin = ApiProviderConfigs.allConfigs(),
                            customs = vm.customProviders,
                            selectedProvider = cfg.provider,
                            selectedCustomName = cfg.customProviderName,
                            onSelectBuiltin = { p ->
                                vm.update {
                                    copy(
                                        provider = p.providerType.name,
                                        baseUrl = p.defaultApiEndpoint,
                                        model = p.defaultModelName.ifBlank { this.model },
                                        customProviderName = "",
                                    )
                                }
                            },
                            onSelectCustom = { cp ->
                                vm.update {
                                    copy(
                                        provider = "OTHER",
                                        baseUrl = cp.baseUrl,
                                        model = cp.defaultModel.ifBlank { this.model },
                                        customProviderName = cp.name,
                                    )
                                }
                            },
                            onAddCustom = { showAddCustom = true },
                        )
                    }

                    item(key = "model-title") { SectionTitle("模型") }
                    item(key = "model-field") {
                        ModelField(modelName = cfg.model, onClick = { vm.showModelPicker = true })
                    }

                    item(key = "apikey-title") { SectionTitle("API Key") }
                    item(key = "apikey-field") {
                        ApiKeyField(
                            value = cfg.apiKey,
                            onValueChange = { vm.update { copy(apiKey = it) } },
                            showSecret = showApiKey,
                            onToggleSecret = { showApiKey = !showApiKey },
                            requiresKey = requiresKey,
                            showError = apiKeyError,
                        )
                    }

                    item(key = "baseurl-title") { SectionTitle("Base URL") }
                    item(key = "baseurl-field") {
                        BaseUrlField(
                            value = cfg.baseUrl,
                            onValueChange = { vm.update { copy(baseUrl = it) } },
                            endpointOptions = endpointOptions,
                        )
                    }

                    item(key = "params-title") { SectionTitle("参数") }
                    item(key = "params-advanced") {
                        AdvancedSettingsSection(cfg = cfg, onUpdate = { block -> vm.update(block) })
                    }

                    item(key = "profiles-title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("已保存预设", modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                profileName = ""
                                vm.showProfileNameDialog = true
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "保存当前配置为预设", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("保存当前配置")
                            }
                        }
                    }
                    item(key = "profiles-content") {
                        if (vm.savedProfiles.isEmpty()) {
                            Text(
                                "还没有预设。保存后可一键切换模型接入点。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.Sm)) {
                                vm.savedProfiles.forEach { p ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(AppRadius.Md),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .clickable { vm.applyProfile(p) }
                                                .padding(horizontal = AppSpace.Md, vertical = AppSpace.Sm),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(p.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                                Text(
                                                    "${p.provider} · ${p.model.ifBlank { "未设置" }}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            IconButton(onClick = { vm.deleteProfile(p.id) }) {
                                                Icon(Icons.Filled.Delete, contentDescription = "删除预设", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "custom-title") { SectionTitle("自定义服务商") }
                    item(key = "custom-content") {
                        if (vm.customProviders.isEmpty()) {
                            Text(
                                "没有自定义服务商。点击服务商区的「添加」创建。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.Sm)) {
                                vm.customProviders.forEach { cp ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(AppRadius.Md),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = AppSpace.Md, vertical = AppSpace.Sm),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(cp.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                                Text(
                                                    cp.baseUrl,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            TextButton(onClick = {
                                                vm.update {
                                                    copy(
                                                        provider = "OTHER",
                                                        baseUrl = cp.baseUrl,
                                                        model = cp.defaultModel.ifBlank { this.model },
                                                        customProviderName = cp.name,
                                                    )
                                                }
                                            }) { Text("使用") }
                                            IconButton(onClick = { vm.deleteCustom(cp.id) }) {
                                                Icon(Icons.Filled.Delete, contentDescription = "删除自定义服务商", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "bottom-spacer") { Spacer(Modifier.height(AppSpace.Sm)) }
                }
            }
        }
    }

    // 模型选择弹窗
    if (vm.showModelPicker) {
        ModelPickerDialog(
            options = vm.modelOptions(),
            currentModel = cfg.model,
            isFetching = vm.isFetchingModels,
            hasCached = (vm.modelsCache as? QuroModelListResult.Success)?.models?.isNotEmpty() == true,
            errorMessage = (vm.modelsCache as? QuroModelListResult.Error)?.message,
            onSelect = { vm.selectModel(it) },
            onFetch = { vm.fetchModels() },
            onManual = { vm.selectModel(it) },
            onDismiss = { vm.showModelPicker = false },
        )
    }

    // 保存预设名称弹窗
    if (vm.showProfileNameDialog) {
        AlertDialog(
            onDismissRequest = { vm.showProfileNameDialog = false },
            title = { Text("保存当前配置为预设") },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("预设名称") },
                    placeholder = { Text("如 我的 DeepSeek") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
            dismissButton = { TextButton(onClick = { vm.showProfileNameDialog = false }) { Text("取消") } },
        )
    }

    // 添加自定义服务商弹窗
    if (showAddCustom) {
        AlertDialog(
            onDismissRequest = { showAddCustom = false },
            title = { Text("添加自定义服务商") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpace.Sm)) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("名称") },
                        placeholder = { Text("如 我的私有服务") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text("默认模型（可选）") },
                        placeholder = { Text("如 deepseek-chat") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
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
                                requiresApiKey = true,
                            )
                        )
                        customName = ""
                        customUrl = ""
                        customModel = ""
                        showAddCustom = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAddCustom = false }) { Text("取消") } },
        )
    }
}