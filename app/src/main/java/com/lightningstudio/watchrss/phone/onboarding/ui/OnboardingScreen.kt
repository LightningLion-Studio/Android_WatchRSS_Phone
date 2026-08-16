package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState
import com.lightningstudio.watchrss.phone.onboarding.ONBOARDING_CATALOG
import com.lightningstudio.watchrss.phone.onboarding.StepType
import com.lightningstudio.watchrss.phone.onboarding.onboardingPhaseLabel
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

/** 屏幕与 ViewModel 之间的动作集合。 */
data class OnboardingActions(
    val onAnswer: (stepId: String, values: List<String>, reason: String?) -> Unit,
    val onSkip: (stepId: String) -> Unit,
    val onAdvance: () -> Unit,
    val onAcceptPolicies: () -> Unit,
    val onRejectPolicies: () -> Unit,
    val onOpenUserAgreement: () -> Unit,
    val onOpenPrivacy: () -> Unit,
    val onOpenPaidAgreement: () -> Unit,
    val onLogin: () -> Unit,
    val onImport: (url: String) -> Unit,
    val onResetImport: () -> Unit,
    val onComplete: () -> Unit
)

@Composable
fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions) {
    WatchRssPhoneTheme {
        Surface(Modifier.fillMaxSize()) {
            AdaptiveWindowScope(Modifier.fillMaxSize()) { windowInfo ->
                AdaptiveContentFrame(
                    windowInfo = windowInfo,
                    mediumMaxWidth = 600.dp,
                    expandedMaxWidth = 640.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp, vertical = 36.dp)
                    ) {
                        OobeProgressHeader(
                            displayStepIndex = state.displayStepIndex,
                            totalSteps = ONBOARDING_CATALOG.size
                        )
                        Spacer(Modifier.height(12.dp))
                        AnimatedContent(
                            targetState = state.displayStepIndex,
                            transitionSpec = {
                                val direction = if (targetState >= initialState) 1 else -1
                                (slideInHorizontally(tween(280)) { fullWidth -> direction * (fullWidth / 4) } +
                                    fadeIn(tween(280)))
                                    .togetherWith(
                                        slideOutHorizontally(tween(280)) { fullWidth -> -direction * (fullWidth / 4) } +
                                            fadeOut(tween(280))
                                    )
                            },
                            label = "onboarding_step"
                        ) { stepIndex ->
                            val step = ONBOARDING_CATALOG.getOrNull(stepIndex) ?: return@AnimatedContent
                            StepHost(step = step, state = state, actions = actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHost(
    step: com.lightningstudio.watchrss.phone.onboarding.OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    when (step.type) {
        StepType.WELCOME -> OnboardingBodyStep(
            icon = Icons.Default.CheckCircle,
            title = step.title,
            body = step.body,
            detail = step.detail,
            actionLabel = "开始设置",
            onAction = actions.onAdvance
        )
        StepType.CONSENT -> ConsentStep(step, actions)
        StepType.CHIP_MULTI -> ChipMultiSelectStep(step, state, actions)
        StepType.CHIP_SINGLE -> ChipSingleSelectStep(step, state, actions)
        StepType.SLIDER -> SliderStep(step, state, actions)
        StepType.TEXT -> TextStep(step, state, actions)
        StepType.NUMBER -> NumberStep(step, state, actions)
        StepType.PLAN_NAME -> PlanNameStep(step, state, actions)
        StepType.ANIMATION -> MagicMomentStep(step, actions)
        StepType.IMPORT_URL -> ImportUrlStep(step, state, actions)
        StepType.IMPORT_RESULT -> ImportResultStep(step, state, actions)
        StepType.FEATURE_PREVIEW -> FeaturePreviewStep(step, actions)
        StepType.LOGIN_GUIDE -> LoginGuideStep(step, state, actions)
        StepType.LOGIN_VIRTUAL -> LoginVirtualStep(step)
        StepType.AUTH_INFO -> OnboardingBodyStep(
            icon = Icons.Default.CheckCircle,
            title = step.title,
            body = step.body,
            detail = step.detail,
            actionLabel = "继续",
            onAction = actions.onAdvance
        )
        StepType.VALUE_RECAP -> ValueRecapStep(step, state, actions)
        StepType.PAYMENT_INTRO -> PaymentIntroStep(step, actions)
        StepType.COMPLETE -> CompleteStep(step, state, actions)
    }
}

/** 通用正文 + 主按钮步骤（欢迎/授权说明等无输入步骤）。 */
@Composable
internal fun OnboardingBodyStep(
    icon: ImageVector,
    title: String,
    body: String,
    detail: String?,
    actionLabel: String,
    onAction: () -> Unit
) {
    StepColumn {
        OnboardingBody(icon = icon, title = title, body = body, detail = detail)
        StepActions {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun StepColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    }
}

@Composable
internal fun StepActions(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

@Composable
internal fun SkipButton(label: String = "跳过", onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun OnboardingBody(
    icon: ImageVector,
    title: String,
    body: String,
    detail: String?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ConsentStep(
    step: com.lightningstudio.watchrss.phone.onboarding.OnboardingStep,
    actions: OnboardingActions
) {
    var checked by rememberSaveable { mutableStateOf(false) }
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.CheckCircle,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                TextButton(
                    onClick = actions.onOpenUserAgreement,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看《用户协议》") }
                TextButton(
                    onClick = actions.onOpenPrivacy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看《隐私政策》") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text("我已阅读并同意《用户协议》和《隐私政策》")
        }
        StepActions {
            Button(
                onClick = actions.onAcceptPolicies,
                enabled = checked,
                modifier = Modifier.fillMaxWidth()
            ) { Text("同意并继续") }
            TextButton(
                onClick = actions.onRejectPolicies,
                modifier = Modifier.fillMaxWidth()
            ) { Text("不同意并退出") }
        }
    }
}

@Composable
private fun LoginVirtualStep(step: com.lightningstudio.watchrss.phone.onboarding.OnboardingStep) {
    StepColumn {
        CircularProgressIndicator(Modifier.padding(24.dp))
        Spacer(Modifier.height(16.dp))
        Text(step.title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            step.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
