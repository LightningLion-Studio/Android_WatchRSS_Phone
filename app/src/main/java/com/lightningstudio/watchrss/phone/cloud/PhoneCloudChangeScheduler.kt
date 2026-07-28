package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.db.SyncChangeLogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class PhoneCloudChangeScheduler(
    private val changeLogDao: SyncChangeLogDao,
    private val cloudSyncService: PhoneCloudSyncService,
    private val scope: CoroutineScope
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            changeLogDao.observeMaxSeq()
                .drop(1)
                .debounce(10 * 60 * 1000L)
                .collect {
                    runCatching { cloudSyncService.syncNow() }
                }
        }
    }
}
