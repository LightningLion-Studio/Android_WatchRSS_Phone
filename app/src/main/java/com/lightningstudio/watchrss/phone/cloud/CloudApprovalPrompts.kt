package com.lightningstudio.watchrss.phone.cloud

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Foreground approver prompts. A pending request only announces itself; keys are wrapped
 * after the user starts, compares the 8-digit code and explicitly confirms the match.
 */
class CloudApprovalPrompts(private val service: () -> PhoneCloudSyncService, private val owner: () -> String?, private val allowed: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private var shown: String? = null
    private var actionInFlight = false
    private val pollRetries = CloudApprovalPollRetries()
    private val ignored = mutableSetOf<String>()

    fun resume(activity: Activity) {
        pause()
        job = scope.launch {
            pollRetries.succeeded()
            while (isActive && !activity.isFinishing && !activity.isDestroyed) {
                if (actionInFlight) { delay(250); continue }
                val approvals = service().approvals
                val state = approvals.state.value
                val busy = state.phase in setOf(CloudApprovalPhase.WAITING, CloudApprovalPhase.COMPARE)
                if (allowed() && owner() != null && !(state.isRequester && busy)) {
                    try { if (busy) approvals.poll() else approvals.discover(); pollRetries.succeeded() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (busy && pollRetries.retry(e)) { delay(pollRetries.delayMillis); continue }
                        if (busy) fail(activity, approvals)
                    }
                    render(activity, approvals)
                } else if (!state.isRequester) close()
                delay(if (busy) 1_500 else 15_000)
            }
        }
    }

    fun pause() { job?.cancel(); job = null; close() }

    private fun close() { dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null; shown = null }

    private fun render(activity: Activity, approvals: CloudApprovalV2) {
        if (actionInFlight || activity.isFinishing || activity.isDestroyed) return
        val state = approvals.state.value
        if (state.isRequester) return
        when (state.phase) {
            CloudApprovalPhase.DISCOVERED -> {
                val request = approvals.pendingRequests.value.firstOrNull { it.id() !in ignored } ?: return close()
                show(activity, "discover:${request.id()}") {
                    setTitle("新设备请求云同步授权")
                    setMessage("${request.name()} 想读取你的云端资料。\n\n只有你本人正在添加这台设备时才继续。继续后，两台设备会显示同一个 8 位码。")
                    setPositiveButton("开始核对") { _, _ -> act(activity, approvals) { approvals.start(request) } }
                    setNegativeButton("忽略") { _, _ -> ignored += request.id(); close() }
                }
            }
            CloudApprovalPhase.WAITING -> show(activity, "wait:${state.requestId}", cancelable = false) {
                setTitle("等待新设备")
                setMessage("请让${state.deviceName()}停留在授权页面，稍后会显示 8 位码。")
                setNegativeButton("取消") { _, _ -> act(activity, approvals) { approvals.reject() } }
            }
            CloudApprovalPhase.COMPARE -> show(activity, "compare:${state.requestId}", cancelable = false) {
                setTitle("核对 8 位码")
                setMessage(codeMessage(state.code.orEmpty(), state.deviceName()))
                setPositiveButton("一致，授权") { _, _ -> act(activity, approvals) { approvals.confirmMatches() } }
                setNegativeButton("不一致") { _, _ -> act(activity, approvals) { approvals.reject() } }
            }
            CloudApprovalPhase.DELIVERED -> finish(activity, approvals, "已授权${state.deviceName()}使用云同步")
            CloudApprovalPhase.CANCELLED -> finish(activity, approvals, "已取消授权")
            CloudApprovalPhase.EXPIRED -> finish(activity, approvals, "授权请求已过期")
            CloudApprovalPhase.ERROR -> fail(activity, approvals)
            CloudApprovalPhase.IDLE -> close()
        }
    }

    private fun show(activity: Activity, key: String, cancelable: Boolean = true, build: AlertDialog.Builder.() -> Unit) {
        if (shown == key && dialog?.isShowing == true) return
        close()
        shown = key
        dialog = AlertDialog.Builder(activity).apply(build).setCancelable(cancelable).create().also {
            it.setOnDismissListener { if (shown == key) { dialog = null; shown = null } }
            it.show()
        }
    }

    private fun act(activity: Activity, approvals: CloudApprovalV2, step: suspend () -> Unit) {
        if (actionInFlight) return
        actionInFlight = true
        close()
        scope.launch {
            try { step(); pollRetries.succeeded() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(activity, approvals) }
            finally { actionInFlight = false; render(activity, approvals) }
        }
    }

    private fun finish(activity: Activity, approvals: CloudApprovalV2, message: String) {
        close(); approvals.clear()
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun fail(activity: Activity, approvals: CloudApprovalV2) =
        finish(activity, approvals, "授权未完成，请在新设备上重新发起")

    private fun codeMessage(code: String, device: String): CharSequence = SpannableStringBuilder().apply {
        append("请确认${device}上显示的是：\n\n")
        val start = length
        append(formatApprovalCode(code))
        setSpan(RelativeSizeSpan(2f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan("monospace"), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("\n\n完全一致才授权。不一致，或不是你本人在操作，请点“不一致”。")
    }

    private fun JSONObject.id() = optString("requestId", optString("id"))
    private fun JSONObject.name() = optString("displayName").ifBlank { "新设备" }.take(60)
    private fun CloudApprovalState.deviceName() = displayName.ifBlank { "新设备" }.take(60)
}

internal fun formatApprovalCode(code: String): String = if (code.length == 8) "${code.take(4)} ${code.takeLast(4)}" else code
