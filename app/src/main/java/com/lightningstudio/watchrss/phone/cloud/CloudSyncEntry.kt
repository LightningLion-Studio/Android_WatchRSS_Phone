package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.lightningstudio.watchrss.phone.PhoneCompanionContainer

@Composable
internal fun rememberCloudSyncEntryAllowed(container: PhoneCompanionContainer): Boolean {
    val service = container.cloudSyncService
    container.accountRepository.session.collectAsState().value
    service.cloudEntryChanges.collectAsState(initial = 0L).value
    container.clientConfig.changes.collectAsState(initial = null).value
    return service.canOpenCloudSync()
}
