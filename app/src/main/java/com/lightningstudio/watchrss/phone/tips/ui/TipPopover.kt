package com.lightningstudio.watchrss.phone.tips.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.lightningstudio.watchrss.phone.tips.TipDefinition
import kotlin.math.roundToInt

private val CardMaxWidth = 300.dp
private val PopoverGap = 12.dp
private val WindowMargin = 8.dp
private val ArrowWidth = 22.dp
private val ArrowHeight = 10.dp

internal val TipPopoverPopupProperties = PopupProperties(
    focusable = false,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false
)

/** Popup 定位交给内部 SubcomposeLayout 全权处理，位置提供器固定为原点。 */
private val TopStartPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}

/**
 * 锚定气泡（TipKit popoverTip 风格）：带箭头的深色卡片，优先显示在锚点下方，
 * 放不下时翻到上方，水平方向钳制在窗口内。
 *
 * 交互：点卡片外部任意处 / 关闭按钮 / 返回键 → onDismiss；点卡片本身不关闭。
 */
@Composable
fun TipPopover(
    definition: TipDefinition,
    anchorBounds: Rect,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val cardColor = MaterialTheme.colorScheme.inverseSurface
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "tip-popover-alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.92f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "tip-popover-scale"
    )

    val cardMaxWidthPx = with(density) { CardMaxWidth.toPx() }
    val gapPx = with(density) { PopoverGap.toPx() }
    val marginPx = with(density) { WindowMargin.toPx() }
    val arrowWidthPx = with(density) { ArrowWidth.toPx() }.roundToInt()
    val arrowHeightPx = with(density) { ArrowHeight.toPx() }.roundToInt()
    val maxCardWidth = minOf(cardMaxWidthPx, windowSize.width - 2 * marginPx).roundToInt().coerceAtLeast(1)

    // A non-focusable Popup cannot receive platform back events. Register at the
    // Activity dispatcher so back dismisses the tip before the underlying screen.
    BackHandler(onBack = onDismiss)

    Popup(
        popupPositionProvider = TopStartPositionProvider,
        onDismissRequest = onDismiss,
        properties = TipPopoverPopupProperties
    ) {
        SubcomposeLayout(
            modifier = Modifier
                .fillMaxSize()
                // 全窗口点击层：点卡片以外任意处关闭（卡片自身吞掉点按）
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }
        ) { constraints ->
            val cardPlaceable = subcompose("card") {
                TipCard(
                    definition = definition,
                    cardColor = cardColor,
                    contentColor = contentColor,
                    onClose = onDismiss
                )
            }.first().measure(Constraints(maxWidth = maxCardWidth))

            // 优先锚点下方，放不下且上方放得下时翻到上方
            val placeAbove = anchorBounds.bottom + gapPx + cardPlaceable.height > windowSize.height - marginPx &&
                anchorBounds.top - gapPx - cardPlaceable.height >= marginPx

            val arrowPlaceable = subcompose("arrow") {
                TipArrow(pointingUp = !placeAbove, color = cardColor)
            }.first().measure(Constraints.fixed(arrowWidthPx, arrowHeightPx))

            val cardX = (anchorBounds.center.x - cardPlaceable.width / 2f).roundToInt()
                .coerceIn(marginPx.roundToInt(), windowSize.width - cardPlaceable.width - marginPx.roundToInt())
            val cardY = if (placeAbove) {
                (anchorBounds.top - gapPx - cardPlaceable.height).roundToInt()
            } else {
                (anchorBounds.bottom + gapPx).roundToInt()
            }
            // 箭头尖对准锚点中心，钳制在卡片范围内
            val arrowCenterX = anchorBounds.center.x.roundToInt()
                .coerceIn(cardX + arrowWidthPx / 2, cardX + cardPlaceable.width - arrowWidthPx / 2)
            val arrowX = arrowCenterX - arrowWidthPx / 2
            val arrowY = if (placeAbove) {
                cardY + cardPlaceable.height - ARROW_OVERLAP_PX
            } else {
                cardY - arrowHeightPx + ARROW_OVERLAP_PX
            }

            layout(windowSize.width, windowSize.height) {
                cardPlaceable.place(cardX, cardY)
                arrowPlaceable.place(arrowX, arrowY)
            }
        }
    }
}

@Composable
private fun TipCard(
    definition: TipDefinition,
    cardColor: Color,
    contentColor: Color,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .widthIn(max = CardMaxWidth)
            // 吞掉点按，防止命中全窗口关闭层
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        contentColor = contentColor,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp, top = 2.dp)
            ) {
                Text(
                    text = definition.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = definition.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun TipArrow(pointingUp: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(ArrowWidth, ArrowHeight)) {
        val w = size.width
        val h = size.height
        val path = if (pointingUp) {
            Path().apply {
                moveTo(w / 2f, 0f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
        } else {
            Path().apply {
                moveTo(w / 2f, h)
                lineTo(w, 0f)
                lineTo(0f, 0f)
                close()
            }
        }
        drawPath(path, color)
    }
}

private const val ARROW_OVERLAP_PX = 2
