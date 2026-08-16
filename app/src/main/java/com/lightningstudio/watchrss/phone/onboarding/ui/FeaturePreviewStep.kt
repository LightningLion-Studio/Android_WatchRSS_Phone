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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep

/**
 * 手表端能力清单。连接手机入口已全量开放（含 release），文案可承诺"已支持"。
 */
private data class CapabilityCard(
    val name: String,
    val description: String
)

private val CAPABILITIES = listOf(
    CapabilityCard("大声朗读", "把文章变成语音，通勤路上用听的"),
    CapabilityCard("AI 总结", "一键生成摘要，30 秒了解全文"),
    CapabilityCard("哔哩哔哩", "在手表上看 B 站视频，点赞投币"),
    CapabilityCard("抖音", "抖音精选，沉浸式竖屏播放")
)

@Composable
internal fun FeaturePreviewStep(step: OnboardingStep, actions: OnboardingActions) {
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.PlayArrow,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CAPABILITIES.forEach { capability ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            capability.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            capability.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
