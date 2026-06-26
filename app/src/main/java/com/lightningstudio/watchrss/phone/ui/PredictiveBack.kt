package com.lightningstudio.watchrss.phone.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

const val PREDICTIVE_BACK_EXIT_PROGRESS = 2f
const val PREDICTIVE_BACK_EXIT_ANIMATION_MS = 140
const val PREDICTIVE_BACK_CANCEL_ANIMATION_MS = 480

@Composable
fun PredictiveBackSurface(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onBeforeBack: suspend () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    val onBeforeBackState = rememberUpdatedState(onBeforeBack)
    val onBackState = rememberUpdatedState(onBack)

    PredictiveBackHandler(enabled = enabled) { backEvents ->
        try {
            backEvents.collect { backEvent ->
                backProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            animate(
                initialValue = backProgress,
                targetValue = PREDICTIVE_BACK_EXIT_PROGRESS,
                animationSpec = tween(PREDICTIVE_BACK_EXIT_ANIMATION_MS)
            ) { value, _ ->
                backProgress = value
            }
            onBeforeBackState.value()
            onBackState.value()
        } catch (exception: CancellationException) {
            animate(
                initialValue = backProgress,
                targetValue = 0f,
                animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
            ) { value, _ ->
                backProgress = value
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .predictiveBackExitPreview(backProgress),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

fun Modifier.predictiveBackExitPreview(progress: Float): Modifier {
    val previewProgress = progress.coerceIn(0f, 1f)
    val exitProgress = (progress - 1f).coerceIn(0f, 1f)
    if (previewProgress <= 0f && exitProgress <= 0f) return this
    return graphicsLayer {
        translationX = 96.dp.toPx() * previewProgress + size.width * exitProgress
        scaleX = 1f - 0.04f * previewProgress
        scaleY = 1f - 0.04f * previewProgress
        alpha = (1f - 0.16f * previewProgress) * (1f - exitProgress)
    }
}
