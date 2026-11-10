package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.syncv2.SyncV2PhoneUiPort
import com.lightningstudio.watchrss.phone.syncv2.SyncV2UiState
import java.util.Calendar

/** T-SYNCV2-UI §2: copy and view rules shared with the watch and the mini program. */
internal object SyncV2View {
    enum class Target { CHOICE, UNOPENABLE, RECOVERY, HISTORY }
    enum class Tone { ATTENTION, ERROR, MUTED }
    data class Notice(val key: String, val title: String, val detail: String, val tone: Tone,
                      val openLabel: String? = null, val target: Target? = null, val retry: String? = null)
    data class Option(val key: String, val label: String, val consequence: String, val enabled: Boolean,
                      val confirmTitle: String? = null, val confirmText: String? = null)

    /** Notices under 云同步, most urgent first. A closed feature shows nothing. */
    fun notices(state: SyncV2UiState?): List<Notice> {
        if (state == null || !state.enabled) return emptyList()
        val out = mutableListOf<Notice>()
        val choice = state.choice
        if (state.phase == "awaiting_choice" && choice != null) {
            out += if (choice.kind == "rollback")
                Notice("choice", "云端已回到较早版本", "本机有 ${choice.localChanges} 项更晚的修改。选择前不会上传。", Tone.ATTENTION, "查看并选择", Target.CHOICE)
            else
                Notice("choice", "需要选择保留哪一份", "本机改了 ${choice.localChanges} 项，云端改了 ${choice.cloudChanges} 项。选择前不会上传。", Tone.ATTENTION, "查看并选择", Target.CHOICE)
        }
        val code = state.errorCode
        when {
            code == "storage_pressure" -> out += Notice("storage", "本机空间不足", "有笔记超出本机可打开的大小，已原样保留在云端。", Tone.ATTENTION)
            state.phase in setOf("error", "blocked") && code == "feature_disabled" -> out += Notice("disabled", "云端暂不支持此操作", "本机内容已保留，稍后会再试。", Tone.MUTED, retry = "重试")
            state.phase in setOf("error", "blocked") && code != null -> out += Notice("error", "同步没有完成", errorText(code), Tone.ERROR, retry = "重试")
        }
        if (state.unopenableNotes.isNotEmpty()) out += Notice("unopenable", "有 ${state.unopenableNotes.size} 条笔记本机无法打开", "这些笔记太大，请在手机或小程序查看。", Tone.MUTED, "查看列表", Target.UNOPENABLE)
        if (state.recoveryCopies.isNotEmpty()) out += Notice("recovery", "可找回的本机修改", "被替换的本机修改保留 7 天。", Tone.MUTED, "查看", Target.RECOVERY)
        if (state.gates.historyRestore) out += Notice("history", "历史版本", "可以把云端资料回到较早的版本。", Tone.MUTED, "查看历史", Target.HISTORY)
        return out
    }

    /** Protocol codes only; service message text is never shown. */
    fun errorText(code: String): String = when (code) {
        "choice_stale" -> "资料刚有变化，请重新查看后再选。"
        "merge_unavailable" -> "这次修改无法自动合并。"
        "window_expired", "temporarily_unavailable" -> "网络或云端暂时不可用，请稍后重试。"
        "quota_exceeded" -> "云空间已满。"
        "note_unopenable" -> "这条笔记太大，本机无法打开。"
        "feature_disabled" -> "云端暂不支持此操作。"
        "trusted_time_unavailable" -> "暂时无法确认云端时间，请联网后重试。"
        "signing_state_unavailable" -> "本机签名状态不可用，暂时只能接收云端修改。"
        else -> "本机内容已保留，请稍后重试。"
    }

    /** Fixed order: 保留云端, 保留本机, 合并两边. One-sided choices confirm by dialog. */
    fun options(preview: SyncV2PhoneUiPort.ChoicePreview, keepDiscarded: Boolean): List<Option> =
        options(preview.local.size + preview.localMore, preview.cloud.size + preview.cloudMore, preview.canMerge, preview.reason, keepDiscarded)

    fun options(local: Int, cloud: Int, canMerge: Boolean, reason: String?, keepDiscarded: Boolean): List<Option> {
        return listOf(
            Option("cloud", "保留云端",
                if (local > 0) "本机 $local 项修改会被替换，${if (keepDiscarded) "7 天内可找回" else "不可找回"}。" else "本机没有要替换的修改。", true,
                "保留云端？", if (local > 0) "本机 $local 项修改会被替换，${if (keepDiscarded) "7 天内可在「可找回的本机修改」中找回" else "替换后不可找回"}。" else "本机没有要替换的修改。"),
            Option("local", "保留本机",
                if (cloud > 0) "云端 $cloud 项修改会被覆盖。" else "云端没有要覆盖的修改。", true,
                "保留本机？", if (cloud > 0) "云端 $cloud 项修改会被本机内容覆盖。" else "云端没有要覆盖的修改。"),
            Option("merge", "合并两边",
                if (canMerge) "两边都保留，冲突的笔记会存两份。" else errorText(reason ?: "merge_unavailable"), canMerge),
        )
    }

    fun kindLabel(kind: String) = when (kind) { "add" -> "新增"; "delete" -> "删除"; else -> "修改" }

    /** Same day → 今天 HH:mm; same year → M月D日 HH:mm; else YYYY年M月D日. */
    fun formatTime(at: Long, now: Long = System.currentTimeMillis()): String {
        val d = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val hm = "%02d:%02d".format(d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))
        val sameYear = d.get(Calendar.YEAR) == n.get(Calendar.YEAR)
        return when {
            sameYear && d.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR) -> "今天 $hm"
            sameYear -> "${d.get(Calendar.MONTH) + 1}月${d.get(Calendar.DAY_OF_MONTH)}日 $hm"
            else -> "${d.get(Calendar.YEAR)}年${d.get(Calendar.MONTH) + 1}月${d.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    /** /history carries no device alias or change count (2026-11-09 ruling). */
    fun historySource(authorDeviceId: String?, ownDeviceId: String?) =
        if (authorDeviceId != null && ownDeviceId != null && authorDeviceId == ownDeviceId) "本机" else "其他设备"
}
