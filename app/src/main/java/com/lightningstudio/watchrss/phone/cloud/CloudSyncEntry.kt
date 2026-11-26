package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer

@Composable
internal fun rememberCloudSyncEntryAllowed(container: PhoneCompanionContainer): Boolean {
    val service = container.cloudSyncService
    // The key belongs to the current account and may arrive from approval or recovery.
    container.accountRepository.session.collectAsState().value
    service.localKeyChanges.collectAsState().value
    container.clientConfig.changes.collectAsState(initial = null).value
    return service.canOpenCloudSync()
}
