package com.lightningstudio.watchrss.phone.tips.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.lightningstudio.watchrss.phone.tips.TipId

/**
 * 当前组合树内各 Tip 锚点的窗口坐标注册表，由 [TipOverlayHost] 提供。
 */
class TipAnchorRegistry internal constructor() {
    private val _bounds = mutableStateMapOf<TipId, Rect>()
    private val _anchorIds = mutableStateOf<Set<TipId>>(emptySet())

    val bounds: Map<TipId, Rect> get() = _bounds
    val anchorIds: Set<TipId> get() = _anchorIds.value

    internal fun update(id: TipId, rect: Rect) {
        _bounds[id] = rect
        if (id !in _anchorIds.value) {
            _anchorIds.value = _anchorIds.value + id
        }
    }

    internal fun remove(id: TipId) {
        _bounds.remove(id)
        _anchorIds.value = _anchorIds.value - id
    }
}

val LocalTipAnchorRegistry = staticCompositionLocalOf<TipAnchorRegistry> {
    error("LocalTipAnchorRegistry 未提供——请用 TipOverlayHost 包裹屏幕")
}

/**
 * 把该控件注册为指定 Tip 的锚点。enabled=false 时不注册，
 * 因此锚点只会存在于真实可见的控件上（如 release 构建中隐藏的按钮）。
 */
fun Modifier.tipAnchor(tipId: TipId, enabled: Boolean = true): Modifier = composed {
    if (enabled) {
        val registry = LocalTipAnchorRegistry.current
        DisposableEffect(tipId) {
            onDispose { registry.remove(tipId) }
        }
        onGloballyPositioned { coordinates ->
            registry.update(tipId, coordinates.boundsInWindow())
        }
    } else {
        this
    }
}
