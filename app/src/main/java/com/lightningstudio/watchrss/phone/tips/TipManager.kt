package com.lightningstudio.watchrss.phone.tips

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 情境提示决策引擎（对应 TipKit 的 Tips 中心）。
 *
 * 规则：锚点集合过滤 → debugShowAll 短路 → dismissed/invalidated/maxShows/频控窗口
 * → 每次启动上限 → 规则求值 → 最高优先级胜出。
 *
 * 展示与关闭语义：
 * - 点外部/关闭按钮/返回键 → [markDismissed]：永久不再显示（持久化）。
 * - 用户完成被教学动作（命中 [TipDefinition.invalidateOnEvents]）→ [invalidate]：自动失效并立即隐藏。
 * - [debugShowAll] 调试模式下不读写任何持久化状态，供截图与验收使用。
 */
class TipManager(
    private val catalog: List<TipDefinition>,
    private val stateBackend: TipStateBackend,
    private val eventBackend: TipEventBackend,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _showingTip = MutableStateFlow<TipId?>(null)
    val showingTip: StateFlow<TipId?> = _showingTip.asStateFlow()

    private var eventSnapshot: Map<String, Long> = eventBackend.snapshot()
    private var tipShownThisLaunch = false

    /** 遥测挂钩（零 PII：只记 tip_shown/tip_dismissed 计数，内容绝不上传）。 */
    var onTipShown: (() -> Unit)? = null
    var onTipDismissed: (() -> Unit)? = null

    var debugShowAll: Boolean
        get() = stateBackend.loadDebugShowAll()
        set(value) = stateBackend.saveDebugShowAll(value)

    fun definition(id: TipId): TipDefinition = catalog.first { it.id == id }

    fun recordEvent(event: String) {
        eventBackend.increment(event)
        eventSnapshot = eventBackend.snapshot()
        val visibleId = _showingTip.value ?: return
        val visibleTip = catalog.firstOrNull { it.id == visibleId } ?: return
        if (event in visibleTip.invalidateOnEvents) {
            invalidate(visibleId)
        }
    }

    /**
     * 评估当前屏锚点中资格最高的一条。已有可见 Tip 时返回 null（单条排队）。
     */
    fun evaluateEligibleTip(anchorIds: Set<TipId>, parameters: TipParameterValues): TipId? {
        if (_showingTip.value != null) return null
        val candidates = catalog.filter { it.id in anchorIds }
        if (candidates.isEmpty()) return null
        if (debugShowAll) return candidates.maxByOrNull { it.priority }?.id
        if (tipShownThisLaunch) return null
        if (parameters.bool(TipParameters.SUPPRESS_TIPS)) return null

        val eligible = candidates.filter { tip ->
            val state = stateBackend.loadAll()[tip.id] ?: TipState()
            if (state.dismissed || state.invalidated) return@filter false
            if (state.showCount >= tip.maxShows) return@filter false
            val window = tip.displayFrequency.periodMillis
            if (window > 0L && state.lastShownAtMillis + window > now()) return@filter false
            tip.rule?.evaluate(parameters, eventSnapshot) ?: true
        }
        return eligible.maxByOrNull { it.priority }?.id
    }

    /** 展示一条 Tip（记录展示次数与时间）。debugShowAll 下不落盘。 */
    fun show(tipId: TipId) {
        if (_showingTip.value == tipId) return
        _showingTip.value = tipId
        if (debugShowAll) return
        tipShownThisLaunch = true
        val current = stateBackend.loadAll()[tipId] ?: TipState()
        stateBackend.save(
            tipId,
            current.copy(lastShownAtMillis = now(), showCount = current.showCount + 1)
        )
        onTipShown?.invoke()
    }

    /** 用户主动关闭：永久不再显示。debugShowAll 下只隐藏不落盘。 */
    fun markDismissed(tipId: TipId) {
        if (_showingTip.value == tipId) _showingTip.value = null
        if (debugShowAll) return
        val current = stateBackend.loadAll()[tipId] ?: TipState()
        if (current.dismissed) return
        stateBackend.save(tipId, current.copy(dismissed = true))
        onTipDismissed?.invoke()
    }

    /** 用户完成被教学动作：自动失效。debugShowAll 下只隐藏不落盘。 */
    fun invalidate(tipId: TipId) {
        if (_showingTip.value == tipId) _showingTip.value = null
        if (debugShowAll) return
        val current = stateBackend.loadAll()[tipId] ?: TipState()
        if (current.invalidated) return
        stateBackend.save(tipId, current.copy(invalidated = true))
    }

    /** 锚点移出组合（切页/关阅读器）或转场时隐藏，不改变任何持久化状态。 */
    fun hide() {
        _showingTip.value = null
    }

    /** 清空全部状态与事件（调试入口「重置新手提示」）。 */
    fun resetTips() {
        stateBackend.clear()
        eventBackend.clear()
        eventSnapshot = emptyMap()
        tipShownThisLaunch = false
        _showingTip.value = null
    }
}
