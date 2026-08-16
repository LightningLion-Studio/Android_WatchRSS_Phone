package com.lightningstudio.watchrss.phone.tips.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import com.lightningstudio.watchrss.phone.tips.TipCatalog
import com.lightningstudio.watchrss.phone.tips.TipManager
import com.lightningstudio.watchrss.phone.tips.TipParameterValues

/** 由 [TipOverlayHost] 提供；宿主之外的组合（如预览）为 null。 */
val LocalTipManager = staticCompositionLocalOf<TipManager?> { null }

/** 页面转场标记：转场期间宿主暂停展示新 Tip，由页面（如 MainScreen）写入。 */
class TipSuppressionState {
    var active by mutableStateOf(false)
}

/**
 * 屏幕级宿主：每个 Activity 在根部包裹一个，提供锚点注册表、
 * 按当前屏锚点评估资格并渲染唯一可见的气泡。
 *
 * - 锚点移出组合（切页/关阅读器）→ 隐藏当前 Tip（不落盘）。
 * - 锚点滚出窗口 → 气泡不渲染，滚回后恢复。
 * - [parameters] 每次状态变化都会触发资格重评估（已有可见 Tip 时不会挤掉）。
 */
@Composable
fun TipOverlayHost(
    tipManager: TipManager,
    parameters: TipParameterValues = TipParameterValues.EMPTY,
    content: @Composable () -> Unit
) {
    val registry = remember { TipAnchorRegistry() }
    val showingId by tipManager.showingTip.collectAsState()
    val anchorIds = registry.anchorIds
    val windowSize = LocalWindowInfo.current.containerSize

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalTipAnchorRegistry provides registry,
            LocalTipManager provides tipManager
        ) {
            content()
        }

        LaunchedEffect(anchorIds, parameters, showingId) {
            if (showingId == null) {
                tipManager.evaluateEligibleTip(anchorIds, parameters)?.let { tipManager.show(it) }
            }
        }

        LaunchedEffect(showingId, anchorIds) {
            if (showingId != null && showingId !in anchorIds) {
                tipManager.hide()
            }
        }

        val definition = showingId?.let { TipCatalog.byId(it) }
        val bounds = showingId?.let { registry.bounds[it] }
        if (definition != null && bounds != null) {
            val windowRect = Rect(0f, 0f, windowSize.width.toFloat(), windowSize.height.toFloat())
            if (bounds.overlaps(windowRect)) {
                TipPopover(
                    definition = definition,
                    anchorBounds = bounds,
                    onDismiss = { tipManager.markDismissed(definition.id) }
                )
            }
        }
    }
}
