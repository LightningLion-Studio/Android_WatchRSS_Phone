package com.lightningstudio.watchrss.phone.rollout

import androidx.compose.foundation.layout.Row
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun B2RolloutSettings(repository: ClientConfigRepository) {
    var override by remember(repository) { mutableStateOf(repository.debugOverride(FeatureRegistry.b2)) }
    val options = listOf(null to "云端配置", FeatureOverride.ON to "强制开启", FeatureOverride.OFF to "强制关闭")
    Text("B2 放量覆盖", style = MaterialTheme.typography.titleSmall)
    Row {
        for ((value, label) in options) {
            TextButton(enabled = override != value, onClick = {
                repository.setDebugOverride(FeatureRegistry.b2, value)
                override = value
            }) { Text(label) }
        }
    }
}
