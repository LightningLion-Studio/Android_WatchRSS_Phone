package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

/**
 * 登录引导：投入已足够深，登录是自然动作而非门槛。
 * "暂不登录"是刻意选择而非"跳过"——完成永不阻塞，LoggedOut 由付费墙兜底。
 */
@Composable
internal fun LoginGuideStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Lock,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        StepActions {
            Button(
                onClick = actions.onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("登录账号") }
            TextButton(onClick = actions.onAdvance) { Text("暂不登录") }
        }
    }
}
