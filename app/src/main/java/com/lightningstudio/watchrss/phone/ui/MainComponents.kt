package com.lightningstudio.watchrss.phone.ui

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.effects.*
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable

// ==================== 液态玻璃效果抽象 ====================

fun Modifier.liquidGlassBackdrop(
    backdrop: LayerBackdrop,
    shape: () -> Shape,
    surfaceAlpha: Float = 0.5f
) = drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        vibrancy()
        blur(8f.dp.toPx())
        if (size != androidx.compose.ui.geometry.Size.Unspecified && size.minDimension > 0f) {
            lens(16f.dp.toPx(), 32f.dp.toPx())
        }
    },
    onDrawSurface = {
        drawRect(Color.White.copy(alpha = surfaceAlpha))
    }
)

// ==================== Glass 顶部导航栏 ====================

@Composable
fun GlassTopBar(
    backdrop: LayerBackdrop,
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val isTopLevel = onBack == null
    val surfaceAlpha = if (isSystemInDarkTheme()) 0.10f else 0.3f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(0.dp) },
                surfaceAlpha = surfaceAlpha
            )
            .padding(
                horizontal = if (isTopLevel) 20.dp else 8.dp,
                vertical = if (isTopLevel) 16.dp else 8.dp
            )
    ) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        AdaptiveWindowScope(modifier = Modifier.fillMaxWidth()) { windowInfo ->
            Row(
                modifier = Modifier.adaptiveContentWidth(
                    windowInfo = windowInfo,
                    mediumMaxWidth = 720.dp,
                    expandedMaxWidth = 840.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                Text(
                    text = title,
                    style = if (isTopLevel) MaterialTheme.typography.headlineMedium
                           else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                actions()
            }
        }
    }
    }
}

// ==================== 胶囊浮动按钮（右下角） ====================

@Composable
fun CapsuleFloatingButton(
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val surfaceAlpha = if (isSystemInDarkTheme()) 0.12f else 0.5f
    val isDark = isSystemInDarkTheme()
    val contentColor = if (isDark) Color.White else Color.Black
    Box(
        modifier = modifier
            .liquidGlassBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(percent = 50) },
                surfaceAlpha = surfaceAlpha
            )
            .roundedClickable(
                shape = RoundedCornerShape(percent = 50),
                enabled = enabled,
                onClick = onClick
            )
            .padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp))
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }
        }
    }
}

// ==================== 页面布局 ====================

@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp,
    bottomSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    AdaptiveWindowScope(modifier = modifier.fillMaxSize()) { windowInfo ->
        AdaptiveContentFrame(
            windowInfo = windowInfo,
            mediumMaxWidth = 720.dp,
            expandedMaxWidth = 840.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (topSpacing > 0.dp) {
                    Spacer(modifier = Modifier.height(topSpacing))
                }
                content()
                if (bottomSpacing > 0.dp) {
                    Spacer(modifier = Modifier.height(bottomSpacing))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshablePageColumn(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp,
    bottomSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        PageColumn(
            modifier = modifier,
            topSpacing = topSpacing,
            bottomSpacing = bottomSpacing,
            content = content
        )
    }
}
