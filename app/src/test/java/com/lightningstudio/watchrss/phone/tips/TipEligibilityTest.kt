package com.lightningstudio.watchrss.phone.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipEligibilityTest {

    private class FakeStateBackend : TipStateBackend {
        val states = mutableMapOf<TipId, TipState>()
        var debugShowAll = false
        override fun loadAll(): Map<TipId, TipState> = states.toMap()
        override fun save(tipId: TipId, state: TipState) { states[tipId] = state }
        override fun clear() { states.clear() }
        override fun loadDebugShowAll(): Boolean = debugShowAll
        override fun saveDebugShowAll(enabled: Boolean) { debugShowAll = enabled }
    }

    private class FakeEventBackend : TipEventBackend {
        val counts = mutableMapOf<String, Long>()
        override fun snapshot(): Map<String, Long> = counts.toMap()
        override fun increment(event: String) { counts[event] = (counts[event] ?: 0L) + 1 }
        override fun clear() { counts.clear() }
    }

    private var currentTime = 1_000_000_000L

    private fun manager(catalog: List<TipDefinition>): Triple<TipManager, FakeStateBackend, FakeEventBackend> {
        val state = FakeStateBackend()
        val events = FakeEventBackend()
        val manager = TipManager(catalog, state, events, now = { currentTime })
        return Triple(manager, state, events)
    }

    private fun tip(
        id: String,
        priority: Int = 0,
        rule: TipRule? = null,
        frequency: TipDisplayFrequency = TipDisplayFrequency.DAILY,
        maxShows: Int = Int.MAX_VALUE,
        invalidateOnEvents: Set<String> = emptySet()
    ) = TipDefinition(
        id = id,
        title = "标题 $id",
        message = "说明 $id",
        priority = priority,
        rule = rule,
        displayFrequency = frequency,
        maxShows = maxShows,
        invalidateOnEvents = invalidateOnEvents
    )

    @Test
    fun `highest priority eligible tip wins`() {
        val (manager, _, _) = manager(listOf(tip("low", 1), tip("high", 10)))
        assertEquals("high", manager.evaluateEligibleTip(setOf("low", "high"), TipParameterValues.EMPTY))
    }

    @Test
    fun `anchors not present on screen are never returned`() {
        // b 优先级更高，但未锚定在当前屏上，不能胜出
        val (manager, _, _) = manager(listOf(tip("a", priority = 1), tip("b", priority = 10)))
        assertEquals("a", manager.evaluateEligibleTip(setOf("a"), TipParameterValues.EMPTY))
        assertNull(manager.evaluateEligibleTip(emptySet(), TipParameterValues.EMPTY))
    }

    @Test
    fun `dismissed and invalidated tips are suppressed`() {
        val (manager, state, _) = manager(listOf(tip("dismissed"), tip("invalidated")))
        manager.markDismissed("dismissed")
        manager.invalidate("invalidated")
        assertNull(manager.evaluateEligibleTip(setOf("dismissed", "invalidated"), TipParameterValues.EMPTY))
        assertTrue(state.states["dismissed"]!!.dismissed)
        assertTrue(state.states["invalidated"]!!.invalidated)
    }

    @Test
    fun `daily frequency window blocks until elapsed`() {
        val (manager, state, _) = manager(listOf(tip("daily")))
        // 上次展示在 1 小时前：仍在窗口内
        state.states["daily"] = TipState(lastShownAtMillis = currentTime - 3_600_000L)
        assertNull(manager.evaluateEligibleTip(setOf("daily"), TipParameterValues.EMPTY))
        // 窗口流逝后恢复资格
        currentTime += 24 * 60 * 60 * 1000L + 1L
        assertEquals("daily", manager.evaluateEligibleTip(setOf("daily"), TipParameterValues.EMPTY))
    }

    @Test
    fun `max shows caps lifetime displays`() {
        val (manager, state, _) = manager(listOf(tip("once", maxShows = 1)))
        state.states["once"] = TipState(showCount = 1)
        assertNull(manager.evaluateEligibleTip(setOf("once"), TipParameterValues.EMPTY))
    }

    @Test
    fun `at most one tip shows per launch`() {
        val (manager, _, _) = manager(listOf(tip("a", priority = 1), tip("b", priority = 2)))
        manager.show("a")
        manager.hide()
        assertNull(manager.evaluateEligibleTip(setOf("a", "b"), TipParameterValues.EMPTY))
    }

    @Test
    fun `suppress tips parameter pauses new tips`() {
        val (manager, _, _) = manager(listOf(tip("a")))
        val parameters = TipParameterValues.Builder().put(TipParameters.SUPPRESS_TIPS, true).build()
        assertNull(manager.evaluateEligibleTip(setOf("a"), parameters))
    }

    @Test
    fun `rule gates eligibility using events snapshot`() {
        val (manager, _, events) = manager(
            listOf(tip("sync", rule = TipRules.allOf(TipRules.eventAtLeast("app_launch", 1), TipRules.eventNever("sync_completed"))))
        )
        assertNull(manager.evaluateEligibleTip(setOf("sync"), TipParameterValues.EMPTY))
        manager.recordEvent("app_launch")
        assertEquals("sync", manager.evaluateEligibleTip(setOf("sync"), TipParameterValues.EMPTY))
        manager.recordEvent("sync_completed")
        assertNull(manager.evaluateEligibleTip(setOf("sync"), TipParameterValues.EMPTY))
        assertEquals(1L, events.counts["sync_completed"])
    }

    @Test
    fun `event matching invalidateOnEvents invalidates visible tip immediately`() {
        val (manager, state, _) = manager(listOf(tip("sync", invalidateOnEvents = setOf("sync_completed"))))
        manager.show("sync")
        assertEquals("sync", manager.showingTip.value)
        manager.recordEvent("sync_completed")
        assertNull(manager.showingTip.value)
        assertTrue(state.states["sync"]!!.invalidated)
    }

    @Test
    fun `debug show all bypasses state and rules but never persists`() {
        val (manager, state, _) = manager(listOf(tip("gated", rule = TipRules.eventAtLeast("never", 5))))
        state.states["gated"] = TipState(dismissed = true)
        manager.debugShowAll = true
        assertEquals("gated", manager.evaluateEligibleTip(setOf("gated"), TipParameterValues.EMPTY))
        manager.show("gated")
        manager.markDismissed("gated")
        assertNull(manager.showingTip.value)
        // debug 模式下不落盘：dismissed 状态保持不变，也没有新增 showCount
        assertTrue(state.states["gated"]!!.dismissed)
        assertEquals(0, state.states["gated"]!!.showCount)
    }

    @Test
    fun `reset tips clears state events and visible tip`() {
        val (manager, state, events) = manager(listOf(tip("a")))
        manager.show("a")
        manager.recordEvent("app_launch")
        manager.resetTips()
        assertNull(manager.showingTip.value)
        assertTrue(state.states.isEmpty())
        assertTrue(events.counts.isEmpty())
        assertEquals("a", manager.evaluateEligibleTip(setOf("a"), TipParameterValues.EMPTY))
    }

    @Test
    fun `shown and dismissed hooks fire once per real transition`() {
        val (manager, _, _) = manager(listOf(tip("a")))
        var shown = 0
        var dismissed = 0
        manager.onTipShown = { shown++ }
        manager.onTipDismissed = { dismissed++ }
        manager.show("a")
        manager.show("a")   // 重复 show 不重复计数
        manager.markDismissed("a")
        manager.markDismissed("a")   // 已关闭不再触发
        assertEquals(1, shown)
        assertEquals(1, dismissed)
        assertNull(manager.evaluateEligibleTip(setOf("a"), TipParameterValues.EMPTY))
    }
}
