package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingProfileBuilder
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

/**
 * 价值回放：把用户亲手写下的内容读给他们听——一致性压力在付费墙前最后一次加压。
 * 跳过多则降级为"未定制"的损失框架文案。
 */
@Composable
internal fun ValueRecapStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    val profile = OnboardingProfileBuilder.buildProfile(state.draft, completedAtMillis = 0L)
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.CheckCircle,
            title = step.title,
            body = if (profile.answeredCount > 0) step.body else "你还没有定制任何内容。",
            detail = step.detail
        )
        Spacer(Modifier.height(16.dp))
        if (profile.answeredCount > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecapCard(
                    label = "计划名",
                    value = profile.planName.ifBlank { "未命名计划" }
                )
                if (profile.primaryScene.isNotBlank()) {
                    RecapCard("主要场景", profile.primaryScene)
                }
                if (profile.dailyTarget.isNotBlank()) {
                    RecapCard("每日目标", "每天 ${profile.dailyTarget} 篇")
                }
                if (profile.commitmentDays.isNotBlank()) {
                    RecapCard("坚持承诺", "${profile.commitmentDays} 天")
                }
                if (profile.whyReadMore.isNotBlank()) {
                    RecapCard("你的初心", profile.whyReadMore)
                }
                if (profile.importedArticleTitle != null) {
                    RecapCard("第一篇文章", "《${profile.importedArticleTitle}》")
                }
                RecapCard("已定制", "${profile.answeredCount} 项")
            }
        } else {
            Text(
                "定制过的计划会在解锁后为你生成：目标、场景与你的初心都会出现在手机和手表上。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StepActions {
            Button(
                onClick = actions.onAdvance,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("继续") }
        }
    }
}

@Composable
private fun RecapCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
