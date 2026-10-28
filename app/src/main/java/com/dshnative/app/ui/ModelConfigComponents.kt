package com.dshnative.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshnative.app.model.ProviderApiConfig
import com.dshnative.app.model.ProviderEndpointOption
import com.dshnative.app.model.QuroCustomProvider
import com.dshnative.app.model.QuroModelConfig
import com.dshnative.app.ui.theme.AppRadius
import com.dshnative.app.ui.theme.AppSpace
import kotlin.math.roundToInt

// ===== 服务商展示辅助 =====

private val PROVIDER_LABELS: Map<String, String> = mapOf(
    "OPENAI" to "OpenAI",
    "OPENAI_RESPONSES" to "OpenAI Responses",
    "OPENAI_RESPONSES_GENERIC" to "OpenAI 兼容",
    "OPENAI_GENERIC" to "OpenAI 兼容",
    "ANTHROPIC" to "Anthropic",
    "ANTHROPIC_GENERIC" to "Anthropic 兼容",
    "GOOGLE" to "Google",
    "GEMINI_GENERIC" to "Gemini 兼容",
    "BAIDU" to "百度",
    "ALIYUN" to "阿里云",
    "XUNFEI" to "讯飞星火",
    "ZHIPU" to "智谱",
    "BAICHUAN" to "百川",
    "MOONSHOT" to "月之暗面",
    "MIMO" to "小米 MiMo",
    "DEEPSEEK" to "DeepSeek",
    "MISTRAL" to "Mistral",
    "SILICONFLOW" to "硅基流动",
    "IFLOW" to "iFlow",
    "OPENROUTER" to "OpenRouter",
    "FOUR_ROUTER" to "4Router",
    "NOUS_PORTAL" to "Nous Portal",
    "INFINIAI" to "无问芯穹",
    "ALIPAY_BAILING" to "支付宝百灵",
    "DOUBAO" to "豆包",
    "NVIDIA" to "NVIDIA",
    "LMSTUDIO" to "LM Studio",
    "OLLAMA" to "Ollama",
    "OPENAI_LOCAL" to "本地 OpenAI",
    "MNN" to "MNN 本地",
    "LLAMA_CPP" to "llama.cpp",
    "PPINFRA" to "派欧云",
    "NOVITA" to "Novita",
    "OPENCODE_GO" to "OpenCode Go",
    "OTHER" to "自定义",
)

/** 服务商展示名（优先中文/官方中文名）。 */
private fun providerLabel(providerTypeName: String): String = PROVIDER_LABELS[providerTypeName] ?: providerTypeName

/** 取服务商首字母（中文取首字，英文取首字母）。 */
private fun initialOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.first()
    return if (first.isLetter()) first.uppercaseChar().toString() else "?"
}

// ===== 区块标题 =====

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

// ===== 服务商选择 =====

@Composable
fun ProviderSelectorRow(
    builtin: List<ProviderApiConfig>,
    customs: List<QuroCustomProvider>,
    selectedProvider: String,
    selectedCustomName: String,
    onSelectBuiltin: (ProviderApiConfig) -> Unit,
    onSelectCustom: (QuroCustomProvider) -> Unit,
    onAddCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpace.Sm),
    ) {
        items(builtin, key = { it.providerType.name }) { p ->
            val label = providerLabel(p.providerType.name)
            val selected = p.providerType.name == selectedProvider
            ProviderCard(
                name = label,
                initial = initialOf(label),
                selected = selected,
                onClick = { onSelectBuiltin(p) },
            )
        }
        items(customs, key = { "custom-${it.id}" }) { cp ->
            val selected = selectedProvider == "OTHER" && selectedCustomName == cp.name
            ProviderCard(
                name = cp.name,
                initial = initialOf(cp.name),
                selected = selected,
                onClick = { onSelectCustom(cp) },
            )
        }
        item(key = "add-custom") {
            AddProviderCard(onClick = onAddCustom)
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    initial: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppRadius.Lg)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val bgColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(88.dp)
            .heightIn(min = 80.dp),
        shape = shape,
        color = bgColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(AppSpace.Xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initial,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AddProviderCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(AppRadius.Lg)
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(88.dp)
            .heightIn(min = 80.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(AppSpace.Xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "添加自定义服务商",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text("添加", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ===== 模型选择 =====

@Composable
fun ModelField(
    modelName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = AppSpace.Md, vertical = AppSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前模型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    modelName.ifBlank { "未选择模型，点击选择" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (modelName.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "选择模型",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ModelPickerDialog(
    options: List<ModelOption>,
    currentModel: String,
    isFetching: Boolean,
    hasCached: Boolean,
    errorMessage: String?,
    onSelect: (String) -> Unit,
    onFetch: () -> Unit,
    onManual: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manual by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.Sm)) {
                if (errorMessage != null && !isFetching) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(AppRadius.Md),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(AppSpace.Sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "错误",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    TextButton(onClick = onFetch, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新拉取", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新拉取")
                    }
                }

                if (!hasCached && !isFetching && errorMessage == null) {
                    Text(
                        "还没有可用模型列表。请先填写 Base URL 并拉取，或直接手动输入模型名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "拉取模型列表", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("拉取模型列表")
                    }
                }

                if (isFetching) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpace.Sm),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }

                if (options.isNotEmpty() && !isFetching) {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(options, key = { "${it.source.name}-${it.id}" }) { opt ->
                            val selected = opt.id == currentModel
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(opt.id) }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = AppSpace.Sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    opt.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (opt.contextLength > 0) {
                                    Text(
                                        "${opt.contextLength / 1024}K",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (opt.source == ModelOptionSource.FALLBACK) {
                                    Text(
                                        "推荐",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "当前模型",
                                        modifier = Modifier.size(18.dp).padding(start = 6.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpace.Sm),
                ) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("手动输入模型名") },
                        placeholder = { Text("如 my-model:latest") },
                        singleLine = true,
                    )
                    Button(
                        onClick = { if (manual.isNotBlank()) onManual(manual.trim()) },
                        enabled = manual.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("使用")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ===== API Key =====

@Composable
fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    showSecret: Boolean,
    onToggleSecret: () -> Unit,
    requiresKey: Boolean,
    showError: Boolean,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text("API Key") },
        placeholder = { Text(if (requiresKey) "必填" else "可选（本地端点可留空）") },
        singleLine = true,
        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = showError,
        supportingText = {
            when {
                showError && requiresKey -> Text("API Key 不能为空")
                !requiresKey -> Text("该端点无需 API Key")
            }
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除 API Key", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = {
                    clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let(onValueChange)
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴 API Key", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggleSecret) {
                    Icon(
                        if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showSecret) "隐藏 API Key" else "显示 API Key",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// ===== Base URL =====

@Composable
fun BaseUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    endpointOptions: List<ProviderEndpointOption>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppSpace.Sm)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            placeholder = { Text("https://api.example.com/v1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        if (endpointOptions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpace.Sm)) {
                items(endpointOptions, key = { it.endpoint }) { opt ->
                    EndpointChip(
                        label = opt.label,
                        selected = value == opt.endpoint,
                        onClick = { onValueChange(opt.endpoint) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppRadius.Md)
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpace.Md, vertical = AppSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选择",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ===== 参数与高级设置 =====

@Composable
fun AdvancedSettingsSection(
    cfg: QuroModelConfig,
    onUpdate: (QuroModelConfig.() -> QuroModelConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Lg),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(vertical = AppSpace.Xs)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = AppSpace.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "高级设置",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起高级设置" else "展开高级设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(horizontal = AppSpace.Md, vertical = AppSpace.Sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpace.Sm),
                ) {
                    SettingSliderRow(
                        label = "温度",
                        valueText = "%.1f".format(cfg.temperature),
                        value = cfg.temperature,
                        valueRange = 0f..2f,
                        steps = 19,
                        onValueChange = { v -> onUpdate { copy(temperature = (v * 10).roundToInt() / 10f) } },
                    )
                    SettingSliderRow(
                        label = "Max Tokens",
                        valueText = tokensLabel(cfg.maxTokens),
                        value = cfg.maxTokens.toFloat(),
                        valueRange = 256f..131072f,
                        steps = 0,
                        onValueChange = { v -> onUpdate { copy(maxTokens = v.roundToInt().coerceIn(256, 131072)) } },
                    )
                    SettingSliderRow(
                        label = "上下文窗口",
                        valueText = contextWindowLabel(cfg.contextWindow),
                        value = cfg.contextWindow.toFloat(),
                        valueRange = 0f..1048576f,
                        steps = 0,
                        onValueChange = { v -> onUpdate { copy(contextWindow = v.roundToInt().coerceIn(0, 1048576)) } },
                    )
                    SettingSliderRow(
                        label = "工具轮次上限",
                        valueText = if (cfg.maxToolRounds == 0) "不限制" else "${cfg.maxToolRounds} 轮",
                        value = cfg.maxToolRounds.toFloat(),
                        valueRange = 0f..20f,
                        steps = 0,
                        onValueChange = { v -> onUpdate { copy(maxToolRounds = v.roundToInt().coerceIn(0, 20)) } },
                    )
                    SettingSliderRow(
                        label = "压缩阈值",
                        valueText = "${cfg.contextCompressThreshold}%",
                        value = cfg.contextCompressThreshold.toFloat(),
                        valueRange = 0f..100f,
                        steps = 0,
                        onValueChange = { v -> onUpdate { copy(contextCompressThreshold = v.roundToInt().coerceIn(0, 100)) } },
                    )
                    SettingSliderRow(
                        label = "软精简阈值",
                        valueText = "${cfg.contextSoftTrimThreshold}%",
                        value = cfg.contextSoftTrimThreshold.toFloat(),
                        valueRange = 0f..100f,
                        steps = 0,
                        onValueChange = { v -> onUpdate { copy(contextSoftTrimThreshold = v.roundToInt().coerceIn(0, 100)) } },
                    )
                    SettingSliderRow(
                        label = "技能工具上限",
                        valueText = cfg.maxSkillTools.toString(),
                        value = cfg.maxSkillTools.toFloat(),
                        valueRange = 1f..64f,
                        steps = 0,
                        onValueChange = { v -> onUpdate { copy(maxSkillTools = v.roundToInt().coerceIn(1, 64)) } },
                    )
                    SettingSwitchRow(
                        label = "启用工具调用",
                        checked = cfg.enableTools,
                        onCheckedChange = { onUpdate { copy(enableTools = it) } },
                    )
                    SettingSwitchRow(
                        label = "完整工具集",
                        checked = cfg.useFullTools,
                        onCheckedChange = { onUpdate { copy(useFullTools = it) } },
                    )
                    SettingSwitchRow(
                        label = "技能可调用",
                        checked = cfg.skillToolsEnabled,
                        onCheckedChange = { onUpdate { copy(skillToolsEnabled = it) } },
                    )
                    if (cfg.modelContextLength > 0) {
                        Text(
                            "当前模型上下文：${cfg.modelContextLength} tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ===== 状态组件 =====

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(AppSpace.Sm))
        Text(
            "正在读取配置…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===== 格式化辅助 =====

private fun tokensLabel(v: Int): String = when {
    v >= 1024 && v % 1024 == 0 -> "${v / 1024}K"
    v >= 1024 -> "${v / 1024}K"
    else -> v.toString()
}

private fun contextWindowLabel(v: Int): String = when {
    v <= 0 -> "不限制"
    v >= 1024 -> "${v / 1024}K"
    else -> v.toString()
}