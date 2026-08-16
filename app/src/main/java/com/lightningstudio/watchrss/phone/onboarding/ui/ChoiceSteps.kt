package com.lightningstudio.watchrss.phone.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.onboarding.OnboardingStep
import com.lightningstudio.watchrss.phone.onboarding.OnboardingUiState

/** 多选 chip 步骤（选择类，必答）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipMultiSelectStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    var selected by rememberSaveable(step.id) {
        mutableStateOf(state.draft.answers[step.echoKey].orEmpty())
    }
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Edit,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)
        ) {
            step.options.forEach { option ->
                val chosen = option in selected
                FilterChip(
                    selected = chosen,
                    onClick = {
                        selected = if (chosen) selected - option else selected + option
                    },
                    label = { Text(option) }
                )
            }
        }
        StepActions {
            Button(
                onClick = {
                    actions.onAnswer(step.id, selected, null)
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("继续") }
        }
    }
}

/** 单选 chip 步骤（选择类，必答）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipSingleSelectStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    var selected by rememberSaveable(step.id) {
        mutableStateOf(state.draft.answers[step.echoKey].orEmpty().firstOrNull())
    }
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Edit,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)
        ) {
            step.options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { selected = option },
                    label = { Text(option) }
                )
            }
        }
        StepActions {
            Button(
                onClick = {
                    selected?.let { actions.onAnswer(step.id, listOf(it), null) }
                },
                enabled = selected != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("继续") }
        }
    }
}

/** 滑块步骤（选择类，必答）。 */
@Composable
internal fun SliderStep(
    step: OnboardingStep,
    state: OnboardingUiState,
    actions: OnboardingActions
) {
    val initial = state.draft.answers[step.echoKey].orEmpty().firstOrNull()?.toIntOrNull()
    var value by rememberSaveable(step.id) {
        mutableStateOf(initial?.coerceIn(step.range) ?: step.range.first)
    }
    StepColumn {
        OnboardingBody(
            icon = Icons.Default.Edit,
            title = step.title,
            body = step.body,
            detail = step.detail
        )
        Spacer(Modifier.height(28.dp))
        Text("$value 篇", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { value = it.toInt() },
            valueRange = step.range.first.toFloat()..step.range.last.toFloat(),
            steps = (step.range.last - step.range.first - 1).coerceAtLeast(0)
        )
        StepActions {
            Button(
                onClick = { actions.onAnswer(step.id, listOf(value.toString()), null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("继续") }
        }
    }
}
