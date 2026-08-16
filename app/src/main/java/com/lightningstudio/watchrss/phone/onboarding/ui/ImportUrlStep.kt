package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.ImportState
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

/**
 * 真实导入任务：粘贴 URL，当场把第一篇文章装进资料库。
 * 这是整个漏斗里最重的一次投入——导入成功后，文章已在库里，撞付费墙时它跑不掉。
 */
@Composable
internal fun ImportUrlStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    var url by rememberSaveable(step.id) { mutableStateOf("") }
    val importState = state.importState
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Download,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        when (importState) {
            ImportState.Idle -> {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…") },
                    label = { Text("文章链接") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    )
                )
                StepActions {
                    Button(
                        onClick = { actions.onImport(url) },
                        enabled = url.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) { Text("导入这篇文章") }
                    SkipButton(label = "跳过，看演示文章", onClick = { actions.onSkip(step.id) })
                }
            }
            ImportState.Loading -> {
                CircularProgressIndicator(Modifier.padding(24.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在抓取正文…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is ImportState.Success -> {
                ImportedArticleCard(title = importState.title)
                StepActions {
                    Button(
                        onClick = actions.onAdvance,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) { Text("继续") }
                }
            }
            is ImportState.Failure -> {
                Text(
                    importState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                StepActions {
                    Button(
                        onClick = { actions.onResetImport(); actions.onImport(url) },
                        enabled = url.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) { Text("重试") }
                    SkipButton(label = "跳过，看演示文章", onClick = { actions.onSkip(step.id) })
                }
            }
        }
    }
}

@Composable
internal fun ImportedArticleCard(title: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "已保存到资料库",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "《$title》",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
