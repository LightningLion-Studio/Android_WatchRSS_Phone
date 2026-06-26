package com.lightningstudio.watchrss.phone.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

fun Modifier.roundedClickable(
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = rememberResettingInteractionSource()
    clip(shape).clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.roundedCombinedClickable(
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    hapticFeedbackEnabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = rememberResettingInteractionSource()
    clip(shape).combinedClickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onDoubleClick = onDoubleClick,
        hapticFeedbackEnabled = hapticFeedbackEnabled,
        onClick = onClick
    )
}

@Composable
private fun rememberResettingInteractionSource(): MutableInteractionSource {
    val interactionSource = remember { MutableInteractionSource() }
    val activePresses = remember { mutableStateListOf<PressInteraction.Press>() }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun cancelActivePresses() {
        activePresses.toList().forEach { press ->
            interactionSource.tryEmit(PressInteraction.Cancel(press))
        }
        activePresses.clear()
    }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> activePresses.add(interaction)
                is PressInteraction.Release -> activePresses.remove(interaction.press)
                is PressInteraction.Cancel -> activePresses.remove(interaction.press)
            }
        }
    }

    DisposableEffect(interactionSource, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                cancelActivePresses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            cancelActivePresses()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return interactionSource
}
