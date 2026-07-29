package com.lightningstudio.watchrss.phone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private const val ADAPTIVE_READER_PANE_TRANSITION_MS = 480

enum class AdaptiveWidthClass {
    Compact,
    Medium,
    Expanded
}

enum class AdaptiveNavigationType {
    BottomBar,
    Rail
}

@Immutable
data class AdaptiveWindowInfo(
    val width: Dp,
    val height: Dp,
    val widthClass: AdaptiveWidthClass
) {
    val navigationType: AdaptiveNavigationType
        get() = if (widthClass == AdaptiveWidthClass.Compact) {
            AdaptiveNavigationType.BottomBar
        } else {
            AdaptiveNavigationType.Rail
        }

    val isMediumOrExpanded: Boolean
        get() = widthClass != AdaptiveWidthClass.Compact

    val isExpanded: Boolean
        get() = widthClass == AdaptiveWidthClass.Expanded
}

@Composable
fun AdaptiveWindowScope(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(AdaptiveWindowInfo) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        content(rememberAdaptiveWindowInfo(maxWidth, maxHeight))
    }
}

@Composable
fun rememberAdaptiveWindowInfo(
    width: Dp,
    height: Dp
): AdaptiveWindowInfo {
    val widthClass = when {
        width < 600.dp -> AdaptiveWidthClass.Compact
        width < 840.dp -> AdaptiveWidthClass.Medium
        else -> AdaptiveWidthClass.Expanded
    }
    return AdaptiveWindowInfo(
        width = width,
        height = height,
        widthClass = widthClass
    )
}

fun Modifier.adaptiveContentWidth(
    windowInfo: AdaptiveWindowInfo,
    compactMaxWidth: Dp = Dp.Unspecified,
    mediumMaxWidth: Dp = 720.dp,
    expandedMaxWidth: Dp = 840.dp
): Modifier {
    val maxWidth = when (windowInfo.widthClass) {
        AdaptiveWidthClass.Compact -> compactMaxWidth
        AdaptiveWidthClass.Medium -> mediumMaxWidth
        AdaptiveWidthClass.Expanded -> expandedMaxWidth
    }
    return if (maxWidth == Dp.Unspecified) {
        this.fillMaxWidth()
    } else {
        this
            .widthIn(max = maxWidth)
            .fillMaxWidth()
    }
}

@Composable
fun AdaptiveContentFrame(
    windowInfo: AdaptiveWindowInfo,
    modifier: Modifier = Modifier,
    mediumMaxWidth: Dp = 720.dp,
    expandedMaxWidth: Dp = 840.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .adaptiveContentWidth(
                    windowInfo = windowInfo,
                    mediumMaxWidth = mediumMaxWidth,
                    expandedMaxWidth = expandedMaxWidth
                )
        ) {
            content()
        }
    }
}

@Composable
fun AdaptiveTwoPane(
    windowInfo: AdaptiveWindowInfo,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp? = null,
    paneSpacing: Dp? = null,
    startPane: @Composable () -> Unit,
    endPane: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = adaptivePaneMetrics(maxWidth, windowInfo)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding ?: metrics.horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(paneSpacing ?: metrics.spacing)
        ) {
            Box(
                modifier = Modifier
                    .width(metrics.startPaneWidth)
                    .fillMaxHeight()
                    .clipToBounds()
            ) {
                startPane()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
            ) {
                endPane()
            }
        }
    }
}

@Composable
fun AdaptiveMovingTwoPane(
    windowInfo: AdaptiveWindowInfo,
    transitionProgress: Float?,
    modifier: Modifier = Modifier,
    startPane: @Composable () -> Unit,
    endPane: @Composable () -> Unit,
    movingPane: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val metrics = adaptivePaneMetrics(maxWidth, windowInfo)
        val endPaneWidth = (maxWidth - metrics.horizontalPadding * 2 - metrics.startPaneWidth - metrics.spacing)
            .coerceAtLeast(metrics.minEndPaneWidth)
        val progress = transitionProgress?.coerceIn(0f, 1f)

        if (progress == null) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = metrics.horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(metrics.spacing)
            ) {
                Box(
                    modifier = Modifier
                        .width(metrics.startPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    startPane()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    endPane()
                }
            }
        } else {
            val startPaneFinalX = metrics.horizontalPadding
            val startPaneInitialX = startPaneFinalX - metrics.startPaneWidth - metrics.spacing
            val movingPaneInitialX = metrics.horizontalPadding
            val movingPaneFinalX = metrics.horizontalPadding + metrics.startPaneWidth + metrics.spacing
            val startPaneX = lerpDp(startPaneInitialX, startPaneFinalX, progress)
            val movingPaneX = lerpDp(movingPaneInitialX, movingPaneFinalX, progress)
            val movingPaneWidth = lerpDp(metrics.startPaneWidth, endPaneWidth, progress)

            Box(
                modifier = Modifier
                    .offset(x = startPaneX)
                    .width(metrics.startPaneWidth)
                    .fillMaxHeight()
                    .clipToBounds()
            ) {
                startPane()
            }
            Box(
                modifier = Modifier
                    .offset(x = movingPaneX)
                    .width(movingPaneWidth)
                    .fillMaxHeight()
                    .clipToBounds()
            ) {
                movingPane()
            }
        }
    }
}

@Composable
fun AdaptiveReaderReturnThreePane(
    windowInfo: AdaptiveWindowInfo,
    progress: Float,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp? = null,
    paneSpacing: Dp? = null,
    startPane: @Composable () -> Unit,
    movingPane: @Composable () -> Unit,
    readerPane: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val metrics = adaptivePaneMetrics(maxWidth, windowInfo)
        val resolvedHorizontalPadding = horizontalPadding ?: metrics.horizontalPadding
        val resolvedPaneSpacing = paneSpacing ?: metrics.spacing
        val endPaneWidth = (maxWidth - resolvedHorizontalPadding * 2 -
            metrics.startPaneWidth - resolvedPaneSpacing)
            .coerceAtLeast(metrics.minEndPaneWidth)
        val returnProgress = progress.coerceIn(0f, 1f)
        val startPaneFinalX = resolvedHorizontalPadding
        val startPaneInitialX = startPaneFinalX - metrics.startPaneWidth - resolvedPaneSpacing
        val movingPaneInitialX = resolvedHorizontalPadding
        val movingPaneFinalX =
            resolvedHorizontalPadding + metrics.startPaneWidth + resolvedPaneSpacing
        val readerPaneInitialX = movingPaneFinalX
        val readerPaneFinalX = maxWidth + resolvedPaneSpacing

        Box(
            modifier = Modifier
                .offset(x = lerpDp(startPaneInitialX, startPaneFinalX, returnProgress))
                .width(metrics.startPaneWidth)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            startPane()
        }
        Box(
            modifier = Modifier
                .offset(x = lerpDp(movingPaneInitialX, movingPaneFinalX, returnProgress))
                .width(lerpDp(metrics.startPaneWidth, endPaneWidth, returnProgress))
                .fillMaxHeight()
                .clipToBounds()
        ) {
            movingPane()
        }
        Box(
            modifier = Modifier
                .offset(x = lerpDp(readerPaneInitialX, readerPaneFinalX, returnProgress))
                .width(endPaneWidth)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            readerPane()
        }
    }
}

@Composable
fun AdaptiveReaderOpenThreePane(
    windowInfo: AdaptiveWindowInfo,
    progress: Float,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp? = null,
    paneSpacing: Dp? = null,
    startPane: @Composable () -> Unit,
    movingPane: @Composable () -> Unit,
    readerPane: @Composable () -> Unit
) {
    AdaptiveReaderReturnThreePane(
        windowInfo = windowInfo,
        progress = 1f - progress.coerceIn(0f, 1f),
        modifier = modifier,
        horizontalPadding = horizontalPadding,
        paneSpacing = paneSpacing,
        startPane = startPane,
        movingPane = movingPane,
        readerPane = readerPane
    )
}

@Composable
fun AdaptiveReadingPane(
    windowInfo: AdaptiveWindowInfo,
    fullscreen: Boolean,
    predictiveBackProgress: Float,
    fullscreenBackProgress: Float = 0f,
    modifier: Modifier = Modifier,
    startPane: @Composable () -> Unit,
    readerPane: @Composable (Boolean) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (!windowInfo.isMediumOrExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .predictiveBackExitPreview(predictiveBackProgress)
            ) {
                readerPane(true)
            }
            return@BoxWithConstraints
        }

        val backPreviewProgress = predictiveBackProgress.coerceIn(0f, 1f)
        val fullscreenCollapseProgress = fullscreenBackProgress.coerceIn(0f, 1f)
        val metrics = adaptivePaneMetrics(maxWidth, windowInfo)
        val splitReaderWidth = (maxWidth - metrics.horizontalPadding * 2 - metrics.startPaneWidth - metrics.spacing)
            .coerceAtLeast(metrics.minEndPaneWidth)
        val splitReaderOffsetX = metrics.horizontalPadding + metrics.startPaneWidth + metrics.spacing
        val animatedFullscreenProgress = animateFloatAsState(
            targetValue = if (fullscreen) 1f else 0f,
            animationSpec = tween(ADAPTIVE_READER_PANE_TRANSITION_MS),
            label = "readerPaneFullscreenProgress"
        )
        val backDrivenFullscreen = fullscreenCollapseProgress > 0f &&
            (fullscreen || animatedFullscreenProgress.value > 0.001f)
        val fullscreenProgress = if (backDrivenFullscreen) {
            (1f - fullscreenCollapseProgress).coerceIn(0f, 1f)
        } else {
            animatedFullscreenProgress.value
        }
        val fullscreenLayerActive = fullscreenProgress > 0.001f || fullscreen
        val readerWidth = lerpDp(splitReaderWidth, maxWidth, fullscreenProgress)
        val readerOffsetX = lerpDp(splitReaderOffsetX, 0.dp, fullscreenProgress)

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .padding(start = metrics.horizontalPadding)
                    .width(metrics.startPaneWidth)
                    .fillMaxHeight()
                    .clipToBounds()
                    .graphicsLayer {
                        translationX = -(metrics.startPaneWidth + metrics.spacing).toPx() * fullscreenProgress
                        alpha = 1f - 0.28f * fullscreenProgress
                    }
            ) {
                startPane()
            }
            Box(
                modifier = Modifier
                    .offset(x = readerOffsetX)
                    .width(readerWidth)
                    .fillMaxHeight()
                    .clipToBounds()
                    .then(
                        if (!fullscreenLayerActive) {
                            Modifier.predictiveBackExitPreview(predictiveBackProgress)
                        } else {
                            Modifier
                        }
                    )
                    .zIndex(1f)
            ) {
                readerPane(fullscreenLayerActive)
            }
        }
    }
}

private data class AdaptivePaneMetrics(
    val horizontalPadding: Dp,
    val spacing: Dp,
    val startPaneWidth: Dp,
    val minEndPaneWidth: Dp
)

private fun adaptivePaneMetrics(
    maxWidth: Dp,
    windowInfo: AdaptiveWindowInfo
): AdaptivePaneMetrics {
    val horizontalPadding = when {
        maxWidth < 620.dp -> 8.dp
        windowInfo.widthClass == AdaptiveWidthClass.Medium -> 12.dp
        else -> 20.dp
    }
    val spacing = when {
        maxWidth < 620.dp -> 8.dp
        windowInfo.widthClass == AdaptiveWidthClass.Medium -> 12.dp
        else -> 16.dp
    }
    val targetStartPaneWidth = when {
        maxWidth < 620.dp -> 256.dp
        windowInfo.widthClass == AdaptiveWidthClass.Medium -> 320.dp
        else -> 384.dp
    }
    val minEndPaneWidth = when {
        maxWidth < 620.dp -> 220.dp
        windowInfo.widthClass == AdaptiveWidthClass.Medium -> 320.dp
        else -> 420.dp
    }
    val maxStartPaneWidth = (maxWidth - horizontalPadding * 2 - spacing - minEndPaneWidth)
        .coerceAtLeast(240.dp)
    val startPaneWidth = minOf(targetStartPaneWidth, maxStartPaneWidth)
    return AdaptivePaneMetrics(
        horizontalPadding = horizontalPadding,
        spacing = spacing,
        startPaneWidth = startPaneWidth,
        minEndPaneWidth = minEndPaneWidth
    )
}

private fun lerpDp(
    start: Dp,
    stop: Dp,
    fraction: Float
): Dp = start + (stop - start) * fraction
