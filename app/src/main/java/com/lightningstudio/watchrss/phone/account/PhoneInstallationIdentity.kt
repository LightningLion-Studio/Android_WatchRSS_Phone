package com.lightningstudio.watchrss.phone.account

import android.content.Context
import java.util.UUID

class PhoneInstallationIdentity(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_installation_identity",
        Context.MODE_PRIVATE
    )

    val installId: String
        get() = getOrCreateString(KEY_INSTALL_ID) { UUID.randomUUID().toString() }

    val firstInstalledAtMillis: Long
        get() = getOrCreateLong(KEY_FIRST_INSTALLED_AT) { System.currentTimeMillis() }

    val databaseInitializedAtMillis: Long
        get() = getOrCreateLong(KEY_DATABASE_INITIALIZED_AT) { System.currentTimeMillis() }

    private fun getOrCreateString(key: String, create: () -> String): String {
        val existing = preferences.getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val created = create()
        preferences.edit().putString(key, created).apply()
        return created
    }

    private fun getOrCreateLong(key: String, create: () -> Long): Long {
        val existing = preferences.getLong(key, 0L)
        if (existing > 0L) return existing
        val created = create()
        preferences.edit().putLong(key, created).apply()
        return created
    }

    companion object {
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_FIRST_INSTALLED_AT = "first_installed_at"
        private const val KEY_DATABASE_INITIALIZED_AT = "database_initialized_at"
    }
}

