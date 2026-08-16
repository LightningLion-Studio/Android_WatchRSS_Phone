package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

/** 导入成果展示：真实文章卡片；跳过导入则展示演示文章。 */
@Composable
internal fun ImportResultStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    val importedTitle = state.draft.importedArticleTitle
    val imported = importedTitle != null
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.CheckCircle,
            title = step.title,
            body = if (imported) "《$importedTitle》已保存到你的资料库。" else step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        if (imported) {
            ImportedArticleCard(title = importedTitle!!)
        } else {
            Text(
                "演示文章《把阅读带上手腕：智能手表的碎片阅读指南》",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
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
