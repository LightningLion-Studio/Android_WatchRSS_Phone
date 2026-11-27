package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Every waiting state on 云同步 uses these two shapes: one spinner size, one stroke, one tone. */
internal const val CLOUD_PROGRESS_TAG = "cloud-progress"

/** A status line for work the user is waiting on: spinner first, then what is happening. */
@Composable
internal fun CloudLoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(Modifier.size(18.dp).testTag(CLOUD_PROGRESS_TAG), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Button content: [idle] at rest; while [running] is set, a compact spinner in the button's own
 * content color (so it follows the disabled tint) and the in-progress wording.
 */
@Composable
internal fun CloudButtonLabel(idle: String, running: String?) {
    if (running == null) {
        Text(idle)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(
            Modifier.size(16.dp).testTag(CLOUD_PROGRESS_TAG),
            color = LocalContentColor.current,
            strokeWidth = 2.dp
        )
        Text(running)
    }
}
