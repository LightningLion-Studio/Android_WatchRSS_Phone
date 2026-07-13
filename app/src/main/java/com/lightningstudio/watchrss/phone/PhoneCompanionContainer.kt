package com.lightningstudio.watchrss.phone

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.EncryptedAccountSessionStore
import com.lightningstudio.watchrss.phone.account.PhoneAccountClient
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneInstallationIdentity
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.connection.guided.PhoneGuidedSessionManager
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.importer.AndroidWebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.PhoneDeviceIdentity
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PhoneCompanionContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountEnvironment: AccountEnvironment by lazy {
        AccountEnvironment()
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
            PhoneCompanionDatabase.MIGRATION_7_8
        )
            .build()
    }

    private val deviceIdentity: PhoneDeviceIdentity by lazy {
        PhoneDeviceIdentity(appContext)
    }

    private val installationIdentity: PhoneInstallationIdentity by lazy {
        PhoneInstallationIdentity(appContext)
    }

    val accountRepository: PhoneAccountRepository by lazy {
        PhoneAccountRepository(
            environment = accountEnvironment,
            sessionStore = EncryptedAccountSessionStore(appContext),
            installationIdentity = installationIdentity,
            accountClient = PhoneAccountClient(accountEnvironment),
            phoneDeviceId = deviceIdentity.deviceId
        )
    }

    val usageTelemetry: PhoneUsageTelemetry by lazy {
        PhoneUsageTelemetry(
            context = appContext,
            environment = accountEnvironment,
            installationIdentity = installationIdentity,
            accountRepository = accountRepository,
            appScope = appScope
        )
    }

    val bluetoothDebugLog: BluetoothDebugLog by lazy {
        BluetoothDebugLog(appContext)
    }

    private val webArticleImporter: AndroidWebArticleImporter by lazy {
        AndroidWebArticleImporter(appContext)
    }

    private val articleContentStore: FileArticleContentStore by lazy {
        FileArticleContentStore(appContext)
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
            articleContentStore = articleContentStore
        )
    }

    val backupService: WatchRssBackupService by lazy {
        WatchRssBackupService(
            context = appContext,
            database = database,
            repository = repository,
            deviceId = deviceIdentity.deviceId
        )
    }

    val guidedSessionManager: PhoneGuidedSessionManager by lazy {
        PhoneGuidedSessionManager(
            context = appContext,
            repository = repository
        )
    }

    val bluetoothSyncManager: PhoneBluetoothSyncManager by lazy {
        PhoneBluetoothSyncManager(
            context = appContext,
            repository = repository,
            deviceId = deviceIdentity.deviceId,
            debugLog = bluetoothDebugLog,
            buildAccountSyncRequest = accountRepository::buildAccountSyncRequest
        )
    }
}
