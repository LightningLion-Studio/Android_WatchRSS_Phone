package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.onboardingPhaseLabel

/**
 * "第 N / 24 步" 进度头：数字 + 线性进度条 + 阶段标签。
 * 24 个药丸段不可读，线性条 + 计数是更诚实也更有目标梯度感的表达。
 */
@Composable
internal fun OobeProgressHeader(displayStepIndex: Int, totalSteps: Int) {
    val stepNumber = (displayStepIndex + 1).coerceAtMost(totalSteps)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "第 $stepNumber / $totalSteps 步",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = onboardingPhaseLabel(displayStepIndex),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { stepNumber.toFloat() / totalSteps.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
    }
}
