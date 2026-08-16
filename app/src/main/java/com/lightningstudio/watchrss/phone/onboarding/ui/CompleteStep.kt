package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

@Composable
internal fun CompleteStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    OnboardingBodyStep(
        icon = Icons.Default.CheckCircle,
        title = step.title,
        body = step.body,
        detail = step.detail,
        actionLabel = "开始使用",
        onAction = actions.onComplete
    )
    Text(
        text = state.phoneMasked?.let { "账号 $it 已登录。" } ?: "未登录账号；可稍后在设置中登录。",
        modifier = Modifier.fillMaxWidth(),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}
