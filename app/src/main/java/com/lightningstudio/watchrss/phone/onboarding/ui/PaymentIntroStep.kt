package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep

/**
 * 付费前说明：明码标价 + 不自动续费 + 7 天退款 + 付费协议勾选。
 * 这里先建立承诺，实际下单仍在 MainActivity 的付费墙（协议弹窗会再次确认）。
 */
@Composable
internal fun PaymentIntroStep(step: OnboardingStep, actions: OnboardingActions) {
    var checked by rememberSaveable(step.id) { mutableStateOf(false) }
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.ShoppingCart,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = actions.onOpenPaidAgreement, modifier = Modifier.fillMaxWidth()) {
            Text("查看《腕上RSS手机版付费服务协议》")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text("我已阅读并同意付费服务协议")
        }
        StepActions {
            Button(
                onClick = actions.onAdvance,
                enabled = checked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("同意并继续") }
        }
    }
}
