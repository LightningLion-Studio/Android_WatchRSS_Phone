package com.lightningstudio.watchrss.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.effects.*
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable

val TAB_BAR_HEIGHT = 72.dp

enum class MainTab(
    val label: String,
    val icon: @Composable () -> Unit
) {
    HOME("首页", { Icon(Icons.Default.Home, contentDescription = null) }),
    RSS("RSS", { Icon(Icons.Default.RssFeed, contentDescription = null) }),
    NOVEL("小说", { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) })
}

@Composable
fun GlassTabBar(
    backdrop: LayerBackdrop,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TAB_BAR_HEIGHT)
            .liquidGlassBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) },
                surfaceAlpha = if (isDark) 0.10f else 0.50f
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val iconTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .roundedClickable(
                            shape = RoundedCornerShape(16.dp),
                            onClick = { onTabSelected(tab) }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CompositionLocalProvider(LocalContentColor provides iconTint) {
                        tab.icon()
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .roundedClickable(
                shape = RoundedCornerShape(percent = 50),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

/**
 * Liquid Glass 风格 Segment 控件
 * 用于 RSS 页面顶部切换：频道 / 收藏 / 稍后 / 独立
 */
@Composable
fun LiquidGlassSegment(
    backdrop: LayerBackdrop,
    segments: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val isDark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(percent = 50) },
                effects = {
                    vibrancy()
                    blur(12f.dp.toPx())
                    lens(20f.dp.toPx(), 40f.dp.toPx())
                },
                onDrawSurface = {
                    if (isDark) {
                        drawRect(Color.White.copy(alpha = 0.08f))
                    } else {
                        drawRect(Color.White.copy(alpha = 0.30f))
                    }
                }
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) }
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RoundedCornerShape(percent = 50) },
                                    effects = {
                                        vibrancy()
                                        blur(4f.dp.toPx())
                                        lens(8f.dp.toPx(), 16f.dp.toPx())
                                    },
                                    onDrawSurface = {
                                        drawRect(primaryColor.copy(alpha = 0.7f))
                                    }
                                )
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
