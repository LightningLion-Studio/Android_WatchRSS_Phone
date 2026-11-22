package com.lightningstudio.watchrss.phone.rollout

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun B2RolloutSettings(container: PhoneCompanionContainer) {
    val loaded by produceState<Pair<ClientConfigRepository, FeatureOverride?>?>(null, container) {
        val repository = withContext(Dispatchers.IO) { container.clientConfig }
        value = repository to repository.loadDebugOverride(FeatureRegistry.b2)
    }
    val (repository, initialOverride) = loaded ?: return
    var override by remember(repository) { mutableStateOf(initialOverride) }
    var saving by remember(repository) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val options = listOf(null to "云端配置", FeatureOverride.ON to "强制开启", FeatureOverride.OFF to "强制关闭")
    Text("B2 放量覆盖", style = MaterialTheme.typography.titleSmall)
    Row {
        for ((value, label) in options) {
            TextButton(enabled = !saving && override != value, onClick = {
                saving = true
                scope.launch {
                    try {
                        repository.setDebugOverride(FeatureRegistry.b2, value)
                        override = value
                    } finally {
                        saving = false
                    }
                }
            }) { Text(label) }
        }
    }
}
