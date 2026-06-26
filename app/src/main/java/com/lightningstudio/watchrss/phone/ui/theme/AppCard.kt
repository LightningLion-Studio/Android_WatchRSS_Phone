package com.lightningstudio.watchrss.phone.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 应用统一的卡片设计系统:
 * - 渐变光影质感
 * - 阴影悬浮感
 * - 边缘轻微高光立体感
 */

private val CardCornerRadius = 16.dp
private val CardElevation = 8.dp

@Composable
fun appCardGradient(): Brush {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                DarkCardStart,
                DarkCardEnd
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                LightCardStart,
                LightCardEnd
            )
        )
    }
}

@Composable
fun appCardHighlightBorder(): Color {
    return if (isSystemInDarkTheme()) CardHighlightDark else CardHighlightLight
}

@Composable
fun appPinnedListCardGradient(): Brush {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                lerp(DarkCardStart, Color.White, 0.08f),
                lerp(DarkCardEnd, Color.White, 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                lerp(LightCardStart, Color.Black, 0.06f),
                lerp(LightCardEnd, Color.Black, 0.04f)
            )
        )
    }
}

/**
 * 统一的应用卡片组件
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CardCornerRadius),
    elevation: Dp = CardElevation,
    interactionModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val highlightColor = appCardHighlightBorder()

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                spotColor = PrimaryRed.copy(alpha = 0.15f),
                ambientColor = PrimaryRed.copy(alpha = 0.05f)
            )
            .clip(shape)
            .background(appCardGradient())
            .border(
                width = 0.5.dp,
                color = highlightColor,
                shape = shape
            )
            .then(interactionModifier)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            content()
        }
    }
}

/**
 * 主色强调卡片(用于重要操作或状态)
 */
@Composable
fun AppPrimaryCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CardCornerRadius),
    interactionModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val gradient = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                DarkPrimary.copy(alpha = 0.20f),
                DarkPrimaryContainer.copy(alpha = 0.10f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                PrimaryRed.copy(alpha = 0.12f),
                GradientEnd.copy(alpha = 0.08f)
            )
        )
    }
    
    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                spotColor = if (isDark) DarkPrimary.copy(alpha = 0.30f) else PrimaryRed.copy(alpha = 0.25f),
                ambientColor = if (isDark) DarkPrimary.copy(alpha = 0.10f) else PrimaryRed.copy(alpha = 0.1f)
            )
            .clip(shape)
            .background(gradient)
            .border(
                width = 0.5.dp,
                color = if (isDark) DarkPrimary.copy(alpha = 0.5f) else PrimaryRed.copy(alpha = 0.2f),
                shape = shape
            )
            .then(interactionModifier)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            content()
        }
    }
}

/**
 * 列表项卡片(更紧凑) —— 含方向性提亮的液态玻璃轮廓
 */
@Composable
fun AppListCard(
    modifier: Modifier = Modifier,
    interactionModifier: Modifier = Modifier,
    backgroundBrush: Brush? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val highlightAlpha = if (isDark) 0.15f else 0.50f
    val glowAlpha = if (isDark) 0.10f else 0.40f
    val shape = RoundedCornerShape(12.dp)
    val cardBackground = backgroundBrush ?: appCardGradient()

    Box(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = shape,
                spotColor = PrimaryRed.copy(alpha = 0.15f),
                ambientColor = PrimaryRed.copy(alpha = 0.05f)
            )
            .clip(shape)
            .background(cardBackground)
            .drawBehind {
                val width = size.width
                val height = size.height
                val r = 12.dp.toPx()

                // 第 1 层：外发光描边（BlurMaskFilter 柔化轮廓）
                val glowPaint = Paint().apply {
                    color = Color.White.copy(alpha = glowAlpha)
                    style = PaintingStyle.Stroke
                    strokeWidth = 2.dp.toPx()
                    asFrameworkPaint().maskFilter = BlurMaskFilter(
                        6.dp.toPx(), BlurMaskFilter.Blur.NORMAL
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRoundRect(
                        0f, 0f, width, height, r, r,
                        glowPaint.asFrameworkPaint()
                    )
                }

                // 第 2 层：菲涅尔式方向性高光 —— 45° 光源从左上照射
                // 只提亮顶部和左侧边缘，背面自然暗淡
                val highlightPaint = Paint().apply {
                    color = Color.White.copy(alpha = highlightAlpha)
                    style = PaintingStyle.Stroke
                    strokeWidth = 1.2.dp.toPx()
                    asFrameworkPaint().maskFilter = BlurMaskFilter(
                        2.dp.toPx(), BlurMaskFilter.Blur.NORMAL
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawLine(
                        r, 0f, width - r, 0f,
                        highlightPaint.asFrameworkPaint()
                    )
                    canvas.nativeCanvas.drawLine(
                        0f, r, 0f, height - r,
                        highlightPaint.asFrameworkPaint()
                    )
                }
            }
            .then(interactionModifier)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            content()
        }
    }
}

/**
 * 兼容 Material3 Card 的包装
 */
@Composable
fun AppMaterialCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) DarkSurfaceElevated else LightSurface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = CardElevation
        )
    ) {
        Box(
            modifier = Modifier.background(appCardGradient())
        ) {
            content()
        }
    }
}
