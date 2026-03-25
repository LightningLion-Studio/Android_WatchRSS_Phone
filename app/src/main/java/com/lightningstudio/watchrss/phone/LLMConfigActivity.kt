package com.lightningstudio.watchrss.phone

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightningstudio.watchrss.phone.model.LLMConfigRequest
import com.lightningstudio.watchrss.phone.model.LLMProvider
import com.lightningstudio.watchrss.phone.network.NetworkManager

class LLMConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRSSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LLMConfigScreen(
                        onBack = { finish() },
                        onLoadConfig = { callback ->
                            NetworkManager.getLLMConfig { response ->
                                runOnUiThread { callback(response) }
                            }
                        },
                        onSubmit = { config ->
                            NetworkManager.postLLMConfig(config) { response ->
                                runOnUiThread {
                                    if (response?.success == true) {
                                        Toast.makeText(this, "配置已发送到手表", Toast.LENGTH_SHORT).show()
                                        finish()
                                    } else {
                                        Toast.makeText(
                                            this,
                                            response?.message ?: "发送失败，请检查连接",
                                            Toast.LENGTH_SHORT
                                        ).show()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMConfigScreen(
    onBack: () -> Unit,
    onLoadConfig: (callback: (com.lightningstudio.watchrss.phone.model.LLMConfigGetResponse?) -> Unit) -> Unit,
    onSubmit: (LLMConfigRequest) -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    // 表单状态
    var selectedProvider by remember { mutableStateOf(LLMProvider.DEEPSEEK) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }

    var apiKeyVisible by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }

    // 每个 Provider 的默认 model 名
    val defaultModels = mapOf(
        LLMProvider.OPENAI to "gpt-4o-mini",
        LLMProvider.DEEPSEEK to "deepseek-chat",
        LLMProvider.QWEN to "qwen-plus",
        LLMProvider.ZHIPU to "glm-4-flash",
        LLMProvider.CUSTOM to ""
    )

    // 切换 provider 时更新默认 model
    LaunchedEffect(selectedProvider) {
        if (model.isEmpty() || defaultModels.values.contains(model)) {
            model = defaultModels[selectedProvider] ?: ""
        }
    }

    // 进入页面时拉取手表现有配置
    LaunchedEffect(Unit) {
        visible = true
        onLoadConfig { response ->
            isLoading = false
            val data = response?.data ?: return@onLoadConfig
            // 还原已保存的配置到表单
            val provider = LLMProvider.entries.find { it.value == data.provider }
            if (provider != null) selectedProvider = provider
            apiKey = data.apiKey
            model = data.model
            baseUrl = data.baseUrl
            enabled = data.enabled
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("RSS 总结大模型配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在读取手表配置…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 标题区 ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700)) +
                        slideInVertically(
                            initialOffsetY = { -40 },
                            animationSpec = tween(700, easing = FastOutSlowInEasing)
                        )
            ) {
                Column {
                    Text(
                        text = "AI 阅读总结",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "手表阅读 RSS 文章时将自动调用所配置的大模型生成摘要",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 启用开关 ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700, delayMillis = 80))
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (enabled)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "启用 AI 总结",
                                fontWeight = FontWeight.Medium,
                                color = if (enabled)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (enabled) "阅读时自动生成摘要" else "当前已停用",
                                fontSize = 12.sp,
                                color = if (enabled)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                    }
                }
            }

            // ── Provider 下拉 ────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700, delayMillis = 150)) +
                        slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(700, delayMillis = 150, easing = FastOutSlowInEasing)
                        )
            ) {
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("大模型服务商") },
                        trailingIcon = {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        LLMProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    selectedProvider = provider
                                    providerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── API Key ──────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700, delayMillis = 220)) +
                        slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(700, delayMillis = 220, easing = FastOutSlowInEasing)
                        )
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (apiKeyVisible) "隐藏" else "显示"
                            )
                        }
                    }
                )
            }

            // ── 模型名称 ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700, delayMillis = 290)) +
                        slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(700, delayMillis = 290, easing = FastOutSlowInEasing)
                        )
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称") },
                    placeholder = { Text(defaultModels[selectedProvider] ?: "model-name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("留空则使用手表默认模型") }
                )
            }

            // ── 自定义 Base URL（仅 CUSTOM 时显示）──────────────────
            AnimatedVisibility(
                visible = visible && selectedProvider == LLMProvider.CUSTOM,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("自定义 API Base URL") },
                    placeholder = { Text("https://your-endpoint/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("需兼容 OpenAI Chat Completions 格式") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 发送按钮 ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700, delayMillis = 430)) +
                        scaleIn(
                            initialScale = 0.85f,
                            animationSpec = tween(700, delayMillis = 430, easing = FastOutSlowInEasing)
                        )
            ) {
                Button(
                    onClick = {
                        isSending = true
                        onSubmit(
                            LLMConfigRequest(
                                provider = selectedProvider.value,
                                apiKey = apiKey.trim(),
                                model = model.trim(),
                                baseUrl = if (selectedProvider == LLMProvider.CUSTOM) baseUrl.trim() else "",
                                enabled = enabled
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apiKey.isNotBlank() && !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isSending) "发送中…" else "发送配置到手表", fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
