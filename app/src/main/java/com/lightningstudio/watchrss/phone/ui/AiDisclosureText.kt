package com.lightningstudio.watchrss.phone.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.data.ai.AiDisclosureFeature
import com.lightningstudio.watchrss.phone.data.ai.AiDisclosureRepository

@Composable
internal fun AiDisclosureText(feature: AiDisclosureFeature) {
    val app = LocalContext.current.applicationContext as PhoneCompanionApplication
    val text by produceState(AiDisclosureRepository.UNAVAILABLE, app, feature) {
        value = app.container.aiDisclosureRepository.displayText(feature)
    }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
