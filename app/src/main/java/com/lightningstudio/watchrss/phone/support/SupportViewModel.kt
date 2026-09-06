package com.lightningstudio.watchrss.phone.support

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SupportMessage(val id: String, val question: String, val answer: String = "", val sources: String = "[]", val status: String = "pending", val actions: String = "[]", val logState: String = "", val logCode: String = "", val logDetail: String = "")
data class SupportState(
    val user: String? = null, val loading: Boolean = true, val version: String = "", val agreement: String = "",
    val accepted: Boolean = false, val conversation: String? = null, val messages: List<SupportMessage> = emptyList(),
    val busy: Boolean = false, val status: String = "", val error: String? = null
)

class SupportViewModel internal constructor(
    application: Application,
    private val sessions: StateFlow<PhoneAccountSession?>,
    private val client: SupportClient
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        (application as PhoneCompanionApplication).container.accountRepository.session,
        SupportClient(AccountEnvironment.active(application), (application as PhoneCompanionApplication).container.accountRepository.session)
    )
    private val mutable = MutableStateFlow(SupportState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var uploadOperation: Job? = null
    private val receipts = application.getSharedPreferences("support_log_receipts", 0)
    internal var uploadLogs: suspend (String, (String) -> Unit) -> String = { text, progress -> SupportLogUploader(application).upload(text, progress) }
    internal var collectLogs: suspend () -> Pair<String, String> = {
        val app = application as PhoneCompanionApplication
        val watch = kotlinx.coroutines.withTimeoutOrNull(12_000) {
            try { app.container.bluetoothSyncManager.collectWatchDebugLog().text }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        val phone = withContext(Dispatchers.IO) {
            val process = ProcessBuilder("logcat", "-d", "-t", "1500", "--pid=${android.os.Process.myPid()}").start()
            try { process.inputStream.bufferedReader().use { it.readText().takeLast(250_000) } }
            finally { process.destroy() }
        }
        val text = withContext(Dispatchers.IO) { app.container.bluetoothDebugLog.snapshot(watch) + "\n===== Phone application log =====\n" + phone }
        redactSupportLog(text) to if (watch == null) "未能读取手表日志，本次上传了手机诊断日志。" else "已包含手机和手表诊断日志。"
    }

    fun declineLogs(id: String) {
        val message = mutable.value.messages.firstOrNull { it.id == id } ?: return
        if (message.status != "ok" || !requiresLogConsent(message.actions) || message.logState.isNotEmpty()) return
        saveReceipt(id, "declined", "")
        updateMessage(id) { it.copy(logState = "declined") }
    }

    fun agreeLogs(id: String) {
        val user = mutable.value.user ?: return
        val message = mutable.value.messages.firstOrNull { it.id == id } ?: return
        if (message.status != "ok" || !mutable.value.accepted || !requiresLogConsent(message.actions) || message.logState !in listOf("", "failed") || uploadOperation?.isActive == true) return
        updateMessage(id) { it.copy(logState = "uploading", logDetail = "正在收集诊断日志…") }
        uploadOperation = viewModelScope.launch {
            try {
                val (text, detail) = collectLogs()
                val code = uploadLogs(text) { progress -> if (mutable.value.user == user) updateMessage(id) { it.copy(logDetail = progress) } }
                check(code.matches(Regex("[0-9]{6}"))) { "日志上传服务未返回有效报错代码" }
                if (mutable.value.user == user) {
                    saveReceipt(id, "uploaded", code)
                    updateMessage(id) { it.copy(logState = "uploaded", logCode = code, logDetail = detail) }
                }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException && mutable.value.user == user) updateMessage(id) { it.copy(logState = "failed", logDetail = "日志上传超时，请重试或直接寻找人工客服。") }
                else throw e
            }
            catch (_: Exception) {
                if (mutable.value.user == user) updateMessage(id) { it.copy(logState = "failed", logDetail = "日志上传失败，请重试或直接寻找人工客服。") }
            }
        }
    }

    private fun saveReceipt(id: String, status: String, code: String) {
        receipts.edit().putString("${mutable.value.user}:$id", "$status:$code").apply()
    }
    private fun restoreReceipt(message: SupportMessage): SupportMessage {
        mutable.value.messages.firstOrNull { it.id == message.id && it.logState.isNotBlank() }?.let { return message.copy(logState = it.logState, logCode = it.logCode, logDetail = it.logDetail) }
        val parts = receipts.getString("${mutable.value.user}:${message.id}", null)?.split(":", limit = 2) ?: return message
        return message.copy(logState = parts[0], logCode = parts.getOrElse(1) { "" })
    }
    init {
        viewModelScope.launch {
            sessions.collect { session ->
                val user = session?.takeUnless { it.isExpired }?.userId
                if (mutable.value.user != user || mutable.value.loading && user == null) {
                    operation?.cancel(); uploadOperation?.cancel(); client.cancel()
                    mutable.value = SupportState(user = user, loading = user != null)
                    if (user != null) refresh()
                }
            }
        }
    }
    fun refresh() = runOperation { user ->
        val agreement = client.json(user, "/agreement")
        mutable.value = mutable.value.copy(loading = false, version = agreement.getString("version"), agreement = agreement.getString("content"), accepted = agreement.getBoolean("accepted"), error = null)
        if (agreement.getBoolean("accepted")) loadLatest(user)
    }
    fun accept() = runOperation { user ->
        client.json(user, "/consent", JSONObject().put("version", mutable.value.version))
        mutable.value = mutable.value.copy(accepted = true)
        loadLatest(user)
    }
    private suspend fun loadLatest(user: String) {
        val list = client.json(user, "/conversations").getJSONArray("conversations")
        val conversation = mutable.value.conversation ?: if (list.length() > 0) list.getJSONObject(0).getString("id") else null
        val messages = if (conversation == null) emptyList() else parseMessages(client.json(user, "/conversations/$conversation/messages").getJSONArray("messages"))
        mutable.value = mutable.value.copy(conversation = conversation, messages = messages, loading = false)
    }
    fun newConversation() { if (!mutable.value.busy && uploadOperation?.isActive != true) mutable.value = mutable.value.copy(conversation = null, messages = emptyList(), error = null) }
    fun send(text: String, retryId: String? = null) {
        if (mutable.value.busy || !mutable.value.accepted || text.isBlank()) return
        if (text.codePointCount(0, text.length) > 4000) { mutable.value = mutable.value.copy(error = "问题最多 4000 字"); return }
        val id = retryId ?: UUID.randomUUID().toString()
        val question = if (retryId != null) text else text.trim()
        runOperation { user ->
            mutable.value = mutable.value.copy(status = "正在连接客服…", messages = if (mutable.value.messages.any { it.id == id }) mutable.value.messages.map { if (it.id == id) SupportMessage(id, question) else it } else mutable.value.messages + SupportMessage(id, question))
            try {
                val conversation = mutable.value.conversation ?: client.json(user, "/conversations", JSONObject()).getString("id")
                mutable.value = mutable.value.copy(conversation = conversation)
                client.send(user, conversation, id, question) { name, data ->
                    withContext(Dispatchers.Main) {
                        if (mutable.value.user != user) return@withContext
                        when (name) {
                            "status" -> mutable.value = mutable.value.copy(status = data.optString("text"))
                            "delta" -> updateMessage(id) { it.copy(answer = it.answer + data.optString("text")) }
                            "actions" -> updateMessage(id) { it.copy(actions = data.optJSONArray("actions")?.toString() ?: "[]") }
                            "sources" -> updateMessage(id) { it.copy(sources = data.getJSONArray("sources").toString()) }
                            "done" -> updateMessage(id) { it.copy(answer = data.getString("answer"), sources = data.getJSONArray("sources").toString(), status = "ok", actions = data.optJSONArray("actions")?.toString() ?: "[]") }
                        }
                    }
                }
            } catch (e: Exception) {
                updateMessage(id) { it.copy(answer = "", status = "failed") }
                throw e
            }
        }
    }
    private fun updateMessage(id: String, change: (SupportMessage) -> SupportMessage) { mutable.value = mutable.value.copy(messages = mutable.value.messages.map { if(it.id == id) change(it) else it }) }
    private fun runOperation(block: suspend (String) -> Unit) {
        if (operation?.isActive == true) return
        val user = client.identity() ?: run { mutable.value = SupportState(loading = false); return }
        mutable.value = mutable.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { block(user) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (mutable.value.user == user) {
                    val consentExpired = e is SupportFailure && e.code in listOf("support_consent_required", "support_agreement_changed")
                    if (e is SupportFailure && e.code in listOf("invalid_session", "support_login_required")) {
                        mutable.value = SupportState(loading = false, error = e.message)
                    } else {
                        mutable.value = mutable.value.copy(error = e.message ?: "请求失败，请重试", accepted = mutable.value.accepted && !consentExpired)
                        if (consentExpired) {
                            runCatching { client.json(user, "/agreement") }.getOrNull()?.let {
                                mutable.value = mutable.value.copy(version = it.getString("version"), agreement = it.getString("content"))
                            }
                        }
                    }
                }
            } finally { if(mutable.value.user == user) mutable.value = mutable.value.copy(busy = false, loading = false, status = "") }
        }
    }
    override fun onCleared() { uploadOperation?.cancel(); client.cancel() }
    private fun parseMessages(array: JSONArray): List<SupportMessage> = (0 until array.length()).map { index ->
        val v = array.getJSONObject(index)
        restoreReceipt(SupportMessage(v.getString("id"), v.getString("content"), v.getString("answer"), v.getJSONArray("sources").toString(), v.getString("status"), v.optJSONArray("actions")?.toString() ?: "[]"))
    }
}

internal fun requiresLogConsent(raw: String): Boolean {
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
    return (0 until array.length()).any { array.optJSONObject(it)?.optString("kind") == "log_upload_consent" }
}
