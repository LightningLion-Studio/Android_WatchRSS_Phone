package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep

/**
 * 魔术时刻：一篇文章从手机飞向手表。
 * 纯 Canvas + Animatable，无位图/模糊/辉光，低端机安全。
 */
@Composable
internal fun MagicMomentStep(step: OnboardingStep, actions: OnboardingActions) {
    var playId by remember { mutableIntStateOf(0) }
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Watch,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(16.dp))
        WatchFlightAnimation(
            playId = playId,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )
        TextButton(onClick = { playId++ }) { Text("再看一次") }
        StepActions {
            Button(
                onClick = actions.onAdvance,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) { Text("继续") }
        }
    }
}

@Composable
private fun WatchFlightAnimation(playId: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(playId) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 2800, easing = FastOutSlowInEasing))
    }
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val density = this.density
        fun dp(value: Float) = value * density

        // 手机（左侧）
        val phoneWidth = dp(88f)
        val phoneHeight = dp(150f)
        val phoneTopLeft = Offset(dp(12f), size.height / 2f - phoneHeight / 2f)
        val phoneSize = Size(phoneWidth, phoneHeight)
        drawRoundRect(
            color = surfaceVariant,
            topLeft = phoneTopLeft,
            size = phoneSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(18f), dp(18f))
        )
        drawRoundRect(
            color = primary.copy(alpha = 0.14f),
            topLeft = phoneTopLeft + Offset(dp(8f), dp(14f)),
            size = Size(phoneWidth - dp(16f), phoneHeight - dp(28f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(10f), dp(10f))
        )

        // 手表（右侧）：表带 + 圆表盘
        val watchCenter = Offset(size.width - dp(96f), size.height / 2f)
        val faceRadius = dp(56f)
        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset(watchCenter.x - dp(26f), watchCenter.y - faceRadius - dp(34f)),
            size = Size(dp(52f), faceRadius + dp(34f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(14f), dp(14f))
        )
        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset(watchCenter.x - dp(26f), watchCenter.y + dp(0f)),
            size = Size(dp(52f), faceRadius + dp(34f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(14f), dp(14f))
        )
        drawCircle(color = surfaceVariant, radius = faceRadius + dp(6f), center = watchCenter)
        drawCircle(color = primary.copy(alpha = 0.16f), radius = faceRadius, center = watchCenter)

        // 文章卡片沿弧线飞行，渐小，被手表"吸收"
        val t = progress.value
        if (t > 0f) {
            val start = Offset(phoneTopLeft.x + phoneWidth / 2f, phoneTopLeft.y + phoneHeight / 2f)
            val end = watchCenter
            val control = Offset(
                (start.x + end.x) / 2f,
                start.y - dp(70f)
            )
            val position = quadraticPoint(start, control, end, t)
            val scale = 1f - 0.45f * t
            val cardWidth = dp(64f) * scale
            val cardHeight = dp(42f) * scale
            val alpha = if (t < 0.8f) 1f else (1f - (t - 0.8f) / 0.2f)
            drawRoundRect(
                color = primary.copy(alpha = alpha),
                topLeft = Offset(position.x - cardWidth / 2f, position.y - cardHeight / 2f),
                size = Size(cardWidth, cardHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(dp(8f) * scale, dp(8f) * scale)
            )
            // 卡片上的"文字行"
            if (t < 0.8f) {
                drawLine(
                    color = onSurface.copy(alpha = alpha * 0.7f),
                    start = Offset(position.x - cardWidth * 0.32f, position.y - cardHeight * 0.15f),
                    end = Offset(position.x + cardWidth * 0.32f, position.y - cardHeight * 0.15f),
                    strokeWidth = dp(3f) * scale,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = onSurface.copy(alpha = alpha * 0.5f),
                    start = Offset(position.x - cardWidth * 0.32f, position.y + cardHeight * 0.05f),
                    end = Offset(position.x + cardWidth * 0.2f, position.y + cardHeight * 0.05f),
                    strokeWidth = dp(3f) * scale,
                    cap = StrokeCap.Round
                )
            }
            // 吸收脉冲
            if (t > 0.8f) {
                val pulse = (t - 0.8f) / 0.2f
                drawCircle(
                    color = primary.copy(alpha = (1f - pulse) * 0.5f),
                    radius = faceRadius + dp(4f) + pulse * dp(22f),
                    center = watchCenter,
                    style = Stroke(width = dp(3f))
                )
            }
        }
    }
}

private fun quadraticPoint(start: Offset, control: Offset, end: Offset, t: Float): Offset {
    val oneMinusT = 1f - t
    return Offset(
        x = oneMinusT * oneMinusT * start.x + 2f * oneMinusT * t * control.x + t * t * end.x,
        y = oneMinusT * oneMinusT * start.y + 2f * oneMinusT * t * control.y + t * t * end.y
    )
}
