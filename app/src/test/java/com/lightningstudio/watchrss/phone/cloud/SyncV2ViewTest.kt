package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.syncv2.SyncV2UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncV2ViewTest {
    private val on = SyncV2UiState(enabled = true, gates = SyncV2UiState.Gates(historyRestore = true, choiceApply = true))

    @Test fun closedFeatureShowsNothing() {
        assertTrue(SyncV2View.notices(null).isEmpty())
        assertTrue(SyncV2View.notices(SyncV2UiState()).isEmpty())
    }

    @Test fun noticesFollowContractOrder() {
        val state = on.copy(phase = "awaiting_choice", choice = SyncV2UiState.Choice(2, 3, true, null, "concurrent"),
            recoveryCopies = listOf(SyncV2UiState.RecoveryCopy("c", 1, 2)))
        val n = SyncV2View.notices(state)
        assertEquals(listOf("choice", "recovery", "history"), n.map { it.key })
        assertEquals("需要选择保留哪一份", n[0].title)
        assertTrue(n[0].detail.endsWith("选择前不会上传。"))
        assertEquals("云端已回到较早版本", SyncV2View.notices(state.copy(choice = state.choice!!.copy(kind = "rollback")))[0].title)
        val disabled = SyncV2View.notices(on.copy(phase = "error", errorCode = "feature_disabled", gates = SyncV2UiState.Gates()))
        assertEquals(listOf("云端暂不支持此操作" to "重试"), disabled.map { it.title to it.retry })
        val blocked = SyncV2View.notices(on.copy(phase = "blocked", errorCode = "trusted_time_unavailable", gates = SyncV2UiState.Gates()))
        assertEquals("暂时无法确认云端时间，请联网后重试。", blocked.single().detail)
    }

    @Test fun choiceOptionsKeepOrderAndConsequences() {
        val o = SyncV2View.options(local = 3, cloud = 0, canMerge = false, reason = "merge_unavailable", keepDiscarded = true)
        assertEquals(listOf("保留云端", "保留本机", "合并两边"), o.map { it.label })
        assertEquals("本机 3 项修改会被替换，7 天内可找回。", o[0].consequence)
        assertEquals("本机 3 项修改会被替换，不可找回。", SyncV2View.options(3, 0, false, null, false)[0].consequence)
        assertEquals("云端没有要覆盖的修改。", o[1].consequence)
        assertFalse(o[2].enabled)
        assertEquals("这次修改无法自动合并。", o[2].consequence)
        assertNotNull(o[0].confirmTitle); assertNotNull(o[1].confirmTitle); assertNull(o[2].confirmTitle)
    }

    @Test fun historySourceAndTime() {
        assertEquals("本机", SyncV2View.historySource("x", "x"))
        assertEquals("其他设备", SyncV2View.historySource("x", null))
        val now = System.currentTimeMillis()
        assertTrue(SyncV2View.formatTime(now, now).matches(Regex("今天 \\d\\d:\\d\\d")))
    }
}
