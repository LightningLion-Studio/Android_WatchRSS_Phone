package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

@Composable
fun CloudSyncStatusCard(
    status: String,
    detail: String?,
    quotaLabel: String?,
    paused: Boolean,
    canPause: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    onTogglePause: () -> Unit
) {
    val mutedColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_cloud_sync_banner"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "云同步",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (canPause) {
                    OutlinedButton(
                        onClick = onTogglePause,
                        modifier = Modifier.heightIn(min = 36.dp),
                        contentPadding = CompactButtonPadding,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f))
                    ) {
                        Text(if (paused) "继续同步" else "暂停同步")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                quotaLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = status,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedColor
                )
            }
            if (!actionLabel.isNullOrBlank()) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = 48.dp),
                    contentPadding = CompactButtonPadding
                ) {
                    Text(text = actionLabel, maxLines = 1)
                }
            }
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor
                )
            }
        }
    }
}
