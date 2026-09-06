package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

/**
 * 文本输入步骤（自由文本，可跳过）。跳过不会影响本地内容或后续使用。
 */
@Composable
internal fun TextStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    var text by rememberSaveable(step.id) {
        mutableStateOf(state.draft.answers[step.echoKey].orEmpty().firstOrNull().orEmpty())
    }
    val maxChars = step.maxChars ?: 80
    val trimmed = text.trim()
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Edit,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= maxChars) text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("写点什么…") },
            supportingText = { Text("${text.length} / $maxChars") },
            singleLine = false,
            minLines = 2,
            maxLines = 4
        )
        StepActions {
            Button(
                onClick = { actions.onAnswer(step.id, listOf(trimmed), null) },
                enabled = trimmed.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("继续") }
            SkipButton(onClick = { actions.onSkip(step.id) })
        }
    }
}

/** 数字输入步骤（可跳过，附一句话理由）。 */
@Composable
internal fun NumberStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    var number by rememberSaveable(step.id) {
        mutableStateOf(state.draft.answers[step.echoKey].orEmpty().firstOrNull().orEmpty())
    }
    var reason by rememberSaveable("${step.id}_reason") {
        mutableStateOf(state.draft.answers["${step.echoKey}_reason"].orEmpty().firstOrNull().orEmpty())
    }
    val valid = number.toIntOrNull()?.let { it in step.range } == true
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Edit,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = number,
            onValueChange = { input -> if (input.length <= 3) number = input.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("数字") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = reason,
            onValueChange = { if (it.length <= 60) reason = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("为什么是这个数？（选填）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )
        StepActions {
            Button(
                onClick = { actions.onAnswer(step.id, listOf(number), reason) },
                enabled = valid,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("继续") }
            SkipButton(onClick = { actions.onSkip(step.id) })
        }
    }
}

/** 计划名步骤（1-12 字，可跳过）。 */
@Composable
internal fun PlanNameStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    var name by rememberSaveable(step.id) {
        mutableStateOf(state.draft.answers[step.echoKey].orEmpty().firstOrNull().orEmpty())
    }
    val maxChars = step.maxChars ?: 12
    val trimmed = name.trim()
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Edit,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= maxChars) name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：睡前充电计划") },
            supportingText = { Text("${name.length} / $maxChars") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )
        StepActions {
            Button(
                onClick = { actions.onAnswer(step.id, listOf(trimmed), null) },
                enabled = trimmed.isNotBlank() && trimmed.length <= maxChars,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("就用这个名字") }
            SkipButton(onClick = { actions.onSkip(step.id) })
        }
    }
}
