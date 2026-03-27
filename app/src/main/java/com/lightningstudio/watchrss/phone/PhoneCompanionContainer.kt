package com.lightningstudio.watchrss.phone

import android.content.Context
import androidx.room.Room
import com.lightningstudio.watchrss.phone.connection.guided.PhoneGuidedSessionManager
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import okhttp3.OkHttpClient

class PhoneCompanionContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: PhoneCompanionDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            PhoneCompanionDatabase::class.java,
            "watchrss-phone.db"
        ).build()
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    val repository: PhoneCompanionRepository by lazy {
        PhoneCompanionRepository(
            client = okHttpClient,
            savedItemDao = database.phoneSavedItemDao()
        )
    }

    val guidedSessionManager: PhoneGuidedSessionManager by lazy {
        PhoneGuidedSessionManager(
            context = appContext,
            repository = repository
        )
    }
}
