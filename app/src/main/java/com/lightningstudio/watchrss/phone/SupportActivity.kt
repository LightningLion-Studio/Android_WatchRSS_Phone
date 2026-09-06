package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.support.*
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import org.json.JSONArray

class SupportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm = (application as PhoneCompanionApplication).supportViewModel
        setContent {
            WatchRssPhoneTheme {
                val state by vm.state.collectAsState()
                SupportScreen(state, vm, { finish() }, {
                    startActivity(Intent(this, ContactDeveloperActivity::class.java))
                }, {
                    startActivity(Intent(this, AccountActivity::class.java))
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupportScreen(state: SupportState, vm: SupportViewModel, back: () -> Unit, human: () -> Unit, login: () -> Unit) {
    val context = LocalContext.current
    fun navigate(intent: Intent) {
        (context.applicationContext as PhoneCompanionApplication).supportOverlay.activate(vm)
        context.startActivity(intent)
    }
    val openHuman = { (context.applicationContext as PhoneCompanionApplication).supportOverlay.activate(vm); human() }
    var draft by rememberSaveable(state.user) { mutableStateOf("") }
    var sourceDialog by remember(state.user) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.lastOrNull()?.answer, state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = { Text("AI 客服") }, navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, actions = {
                if(state.accepted) TextButton(onClick = vm::newConversation, enabled = !state.busy) { Text("新对话") }
                TextButton(onClick = openHuman) { Text("人工客服") }
            })
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                when {
                    state.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在加载客服…", Modifier.padding(16.dp)) }
                    state.user == null -> {
                        Text("请先登录后使用 AI 客服", Modifier.padding(vertical = 24.dp))
                        Button(onClick = { (context.applicationContext as PhoneCompanionApplication).supportOverlay.activate(vm); login() }) { Text("去登录") }
                    }
                    !state.accepted && state.agreement.isNotBlank() -> {
                        Text("首次使用需同意 AI 客服服务协议", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("support_agreement")) { ReadOnlyMarkdown(state.agreement) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = back, enabled = !state.busy) { Text("不同意，返回") }
                            Button(onClick = vm::accept, enabled = !state.busy, modifier = Modifier.testTag("support_accept")) { Text("同意并使用") }
                        }
                    }
                    state.accepted -> {
                        if(state.messages.isEmpty()) Text("可以询问使用方法、同步问题或您的订单状态。回答会附上检索来源。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("support_messages"), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(state.messages, key = { it.id }) { message ->
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.align(Alignment.End)) {
                                        SelectionContainer { Text(message.question, Modifier.padding(12.dp)) }
                                    }
                                    if(message.answer.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                                        SelectionContainer { ReadOnlyMarkdown(supportAnswerText(message.answer, message.status == "pending")) }
                                    }
                                    if (message.status == "ok" && requiresLogConsent(message.actions)) {
                                        SupportLogConsent(message, { vm.agreeLogs(message.id) }, { vm.declineLogs(message.id) }, openHuman)
                                    }
                                    if (message.status == "ok") {
                                        SupportDestination.fromActions(message.actions).forEach { destination ->
                                            OutlinedButton(
                                                onClick = { navigate(destination.createIntent(context)) },
                                                modifier = Modifier.testTag("support_action_${destination.id}")
                                            ) { Text(destination.label) }
                                        }
                                    }
                                    if(message.status == "ok" && message.sources != "[]") TextButton(onClick = { sourceDialog = message.sources }) { Text("查看回答来源") }
                                    if(message.status == "failed" || message.status == "pending" && !state.busy) TextButton(onClick = { vm.send(message.question, message.id) }, enabled = !state.busy) { Text(if(message.status == "pending") "获取回答 / 重试" else "重试此问题") }
                                }
                            }
                        }
                        if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp)) }
                        TextField(
                            value = draft,
                            onValueChange = { if (it.codePointCount(0, it.length) <= 4000) draft = it },
                            placeholder = { Text("描述您的问题") },
                            maxLines = 5,
                            enabled = !state.busy,
                            shape = RoundedCornerShape(32.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            trailingIcon = {
                                FilledIconButton(
                                    onClick = { vm.send(draft); draft = "" },
                                    enabled = !state.busy && draft.isNotBlank(),
                                    modifier = Modifier.padding(end = 8.dp).size(48.dp).testTag("support_send")
                                ) { Icon(Icons.Default.ArrowUpward, contentDescription = "发送") }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
                                .heightIn(min = 64.dp).testTag("support_input")
                        )
                    }
                }
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    if(!state.busy) TextButton(onClick = vm::refresh) { Text("重新加载") }
                }
            }
        }
    }
    sourceDialog?.let { raw ->
        AlertDialog(onDismissRequest = { sourceDialog = null }, title = { Text("检索来源") }, text = {
            SelectionContainer(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
                Text((0 until array.length()).joinToString("\n\n") { i ->
                    val item = array.getJSONObject(i)
                    if(item.optString("kind") == "knowledge") "${item.optString("source")}:${item.optInt("line")}\n${item.optString("text")}"
                    else historySourceText(item)
                })
            }
        }, confirmButton = { TextButton(onClick = { sourceDialog = null }) { Text("关闭") } })
    }
}

private fun historySourceText(item: org.json.JSONObject): String {
    val summary = item.optJSONObject("summary") ?: org.json.JSONObject()
    val label = when(item.optString("source")) {
        "app_payment_orders" -> "订单状态"
        "payment_events" -> "支付处理记录"
        "admin_operation_audit_log" -> "账号操作记录"
        "phone_passkey_sessions" -> "会话创建记录（非完整登录历史）"
        "daily_usage_rollups" -> "每日同步汇总（非逐次记录）"
        else -> "历史记录"
    }
    val detail = when(item.optString("category")) {
        "sync" -> "同步成功 ${summary.optInt("successCount")} 次，失败 ${summary.optInt("failureCount")} 次"
        "session" -> "记录了会话创建；${if(summary.optBoolean("revoked")) "已撤销" else "未撤销"}"
        "payment" -> "状态：" + when(summary.optString("status")) {
            "pending" -> "待支付"
            "paid", "TRADE_SUCCESS" -> "已支付"
            "refund_pending" -> "退款处理中"
            "refunded" -> "已退款"
            "refund_failed" -> "退款失败"
            "closed" -> "已关闭"
            else -> "已记录，由客服结合上下文解释"
        }
        "account" -> "操作结果：" + when(summary.optString("outcome")) {
            "succeeded" -> "成功"
            "failed" -> "失败"
            "pending" -> "处理中"
            else -> "已请求"
        }
        else -> "已有记录"
    }
    return "$label\n${item.optString("time")}\n$detail"
}
