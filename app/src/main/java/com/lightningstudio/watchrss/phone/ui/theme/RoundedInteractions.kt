package com.lightningstudio.watchrss.phone.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

fun Modifier.roundedClickable(
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = clip(shape).clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick
)

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
): Modifier = clip(shape).combinedClickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onLongClickLabel = onLongClickLabel,
    onLongClick = onLongClick,
    onDoubleClick = onDoubleClick,
    hapticFeedbackEnabled = hapticFeedbackEnabled,
    onClick = onClick
)
