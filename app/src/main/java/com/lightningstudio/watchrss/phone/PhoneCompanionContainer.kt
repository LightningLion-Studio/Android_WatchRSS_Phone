package com.lightningstudio.watchrss.phone

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.EncryptedAccountSessionStore
import com.lightningstudio.watchrss.phone.account.PhoneAccountClient
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.data.ai.PhoneAiSettingsStore
import com.lightningstudio.watchrss.phone.data.ai.PhoneAiSummaryService
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncClient
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneNoteBluetoothSyncManager
import com.lightningstudio.watchrss.phone.connection.guided.PhoneGuidedSessionManager
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudChangeScheduler
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudClient
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.importer.AndroidWebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.PhoneDeviceIdentity
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.telemetry.OpenPanelAnalytics
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneCompanionContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountEnvironment: AccountEnvironment by lazy {
        AccountEnvironment.active(appContext)
    }

    private val database: PhoneCompanionDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            PhoneCompanionDatabase::class.java,
            "watchrss-phone.db"
        ).addMigrations(
            PhoneCompanionDatabase.MIGRATION_1_2,
            PhoneCompanionDatabase.MIGRATION_2_3,
            PhoneCompanionDatabase.MIGRATION_3_4,
            PhoneCompanionDatabase.MIGRATION_4_5,
            PhoneCompanionDatabase.MIGRATION_5_6,
            PhoneCompanionDatabase.MIGRATION_6_7,
            PhoneCompanionDatabase.MIGRATION_7_8,
            PhoneCompanionDatabase.MIGRATION_8_9,
            PhoneCompanionDatabase.MIGRATION_9_10,
            PhoneCompanionDatabase.MIGRATION_10_11,
            PhoneCompanionDatabase.MIGRATION_11_12,
            PhoneCompanionDatabase.MIGRATION_12_13,
            PhoneCompanionDatabase.MIGRATION_13_14
        )
            .build()
    }

    private val deviceIdentity: PhoneDeviceIdentity by lazy {
        PhoneDeviceIdentity(appContext)
    }

    /** Stable peer id shared by RFCOMM and the RTOS BLE note transports. */
    val syncDeviceId: String
        get() = deviceIdentity.deviceId

    private val installationIdentity: PhoneInstallationIdentity by lazy {
        PhoneInstallationIdentity(appContext)
    }

    val firstInstalledAtMillis: Long
        get() = installationIdentity.firstInstalledAtMillis

    val accountRepository: PhoneAccountRepository by lazy {
        PhoneAccountRepository(
            environment = accountEnvironment,
            sessionStore = EncryptedAccountSessionStore(
                appContext,
                prefsName = "watchrss_account_session${accountEnvironment.storageSuffix}"
            ),
            installationIdentity = installationIdentity,
            accountClient = PhoneAccountClient(accountEnvironment),
            phoneDeviceId = deviceIdentity.deviceId
        )
    }

    val openPanelAnalytics: OpenPanelAnalytics by lazy {
        OpenPanelAnalytics(appContext, appScope)
    }

    val usageTelemetry: PhoneUsageTelemetry by lazy {
        PhoneUsageTelemetry(
            context = appContext,
            environment = accountEnvironment,
            installationIdentity = installationIdentity,
            accountRepository = accountRepository,
            appScope = appScope,
            openPanelAnalytics = openPanelAnalytics
        )
    }

    val bluetoothDebugLog: BluetoothDebugLog by lazy {
        BluetoothDebugLog(appContext)
    }

    val readerPresetRepository: ReaderPresetRepository by lazy {
        ReaderPresetRepository(
            context = appContext,
            database = database,
            dao = database.readerPresetDao(),
            deviceId = deviceIdentity.deviceId,
            scope = appScope
        ).also { repository ->
            appScope.launch { repository.ensureSeeded() }
        }
    }

    val aiSettingsStore: PhoneAiSettingsStore by lazy { PhoneAiSettingsStore(appContext) }
    val aiSummaryService: PhoneAiSummaryService by lazy { PhoneAiSummaryService(aiSettingsStore) }

    private val webArticleImporter: AndroidWebArticleImporter by lazy {
        AndroidWebArticleImporter(appContext)
    }

    private val articleContentStore: FileArticleContentStore by lazy {
        FileArticleContentStore(appContext)
    }

    val llmTokenUsageRepository: PhoneLlmTokenUsageRepository by lazy {
        PhoneLlmTokenUsageRepository(
            dao = database.llmTokenUsageDao()
        )
    }

    val repository: PhoneCompanionRepository by lazy {
        PhoneCompanionRepository(
            savedItemDao = database.phoneSavedItemDao(),
            articleDao = database.phoneArticleDao(),
            rssSourceDao = database.phoneRssSourceDao(),
            deviceId = deviceIdentity.deviceId,
            syncChangeLogDao = database.syncChangeLogDao(),
            syncPeerStateDao = database.syncPeerStateDao(),
            webArticleImporter = webArticleImporter::importUrl,
            articleContentStore = articleContentStore,
            appMetaDao = database.appMetaDao()
        )
    }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), deviceIdentity.deviceId)
    }

    val backupService: WatchRssBackupService by lazy {
        WatchRssBackupService(
            context = appContext,
            database = database,
            repository = repository,
            readerPresetRepository = readerPresetRepository,
            deviceId = deviceIdentity.deviceId
        )
    }

    val cloudSyncService: PhoneCloudSyncService by lazy {
        val storageSuffix = accountEnvironment.storageSuffix
        PhoneCloudSyncService(
            context = appContext,
            accountRepository = accountRepository,
            backupService = backupService,
            repository = repository,
            noteRepository = noteRepository,
            deviceId = deviceIdentity.deviceId,
            client = PhoneCloudClient(accountEnvironment),
            keyManager = com.lightningstudio.watchrss.phone.cloud.CloudKeyManager(
                appContext,
                storageSuffix = storageSuffix
            ),
            settings = com.lightningstudio.watchrss.phone.cloud.PhoneCloudStateStore(
                appContext,
                prefsName = "watchrss_cloud_state$storageSuffix"
            ),
            cache = com.lightningstudio.watchrss.phone.cloud.CloudLocalCache(
                appContext,
                directoryName = "cloud-cache$storageSuffix"
            ),
            uploader = com.lightningstudio.watchrss.phone.cloud.SupabaseTusUploader(
                appContext,
                prefsName = "watchrss_tus_uploads$storageSuffix"
            ),
            rssInventoryPreferences =
                com.lightningstudio.watchrss.phone.cloud.CloudRssInventoryPreferences(
                    appContext,
                    prefsName = "watchrss_cloud_rss_inventory$storageSuffix"
                )
        )
    }

    private val cloudChangeScheduler: PhoneCloudChangeScheduler by lazy {
        PhoneCloudChangeScheduler(
            changeLogDao = database.syncChangeLogDao(),
            cloudSyncService = cloudSyncService,
            scope = appScope
        )
    }

    fun startCloudChangeScheduler() {
        cloudChangeScheduler.start()
    }

    val guidedSessionManager: PhoneGuidedSessionManager by lazy {
        PhoneGuidedSessionManager(
            context = appContext,
            repository = repository
        )
    }

    private val bluetoothSyncClient: PhoneBluetoothSyncClient by lazy {
        PhoneBluetoothSyncClient(appContext, bluetoothDebugLog)
    }

    val noteBluetoothSyncManager: PhoneNoteBluetoothSyncManager by lazy {
        PhoneNoteBluetoothSyncManager(noteRepository, bluetoothSyncClient, deviceIdentity.deviceId)
    }

    val bluetoothSyncManager: PhoneBluetoothSyncManager by lazy {
        PhoneBluetoothSyncManager(
            context = appContext,
            repository = repository,
            readerPresetRepository = readerPresetRepository,
            llmTokenUsageRepository = llmTokenUsageRepository,
            deviceId = deviceIdentity.deviceId,
            debugLog = bluetoothDebugLog,
            buildAccountSyncRequest = accountRepository::buildAccountSyncRequest,
            onLibrarySyncCompleted = {
                if (accountRepository.session.value != null) {
                    runCatching { cloudSyncService.syncNow() }
                }
            }
        )
    }
}
