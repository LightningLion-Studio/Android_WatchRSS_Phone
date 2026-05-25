package com.lightningstudio.watchrss.phone

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneBluetoothSyncManager
import com.lightningstudio.watchrss.phone.connection.guided.PhoneGuidedSessionManager
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.importer.AndroidWebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.PhoneDeviceIdentity
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository

class PhoneCompanionContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: PhoneCompanionDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            PhoneCompanionDatabase::class.java,
            "watchrss-phone.db"
        ).addMigrations(
            PhoneCompanionDatabase.MIGRATION_1_2,
            PhoneCompanionDatabase.MIGRATION_2_3
        )
            .build()
    }

    private val deviceIdentity: PhoneDeviceIdentity by lazy {
        PhoneDeviceIdentity(appContext)
    }

    private val webArticleImporter: AndroidWebArticleImporter by lazy {
        AndroidWebArticleImporter(appContext)
    }

    val repository: PhoneCompanionRepository by lazy {
        PhoneCompanionRepository(
            savedItemDao = database.phoneSavedItemDao(),
            articleDao = database.phoneArticleDao(),
            rssSourceDao = database.phoneRssSourceDao(),
            deviceId = deviceIdentity.deviceId,
            webArticleImporter = webArticleImporter::importUrl
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
            deviceId = deviceIdentity.deviceId
        )
    }
}
