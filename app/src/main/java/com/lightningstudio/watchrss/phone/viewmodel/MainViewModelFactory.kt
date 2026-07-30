package com.lightningstudio.watchrss.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry

class MainViewModelFactory(
    private val repository: PhoneCompanionRepository,
    private val bluetoothSyncManager: PhoneBluetoothSyncManager,
    private val llmTokenUsageRepository: PhoneLlmTokenUsageRepository,
    private val usageTelemetry: PhoneUsageTelemetry,
    private val backupService: WatchRssBackupService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                repository,
                bluetoothSyncManager,
                llmTokenUsageRepository,
                usageTelemetry,
                backupService
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
