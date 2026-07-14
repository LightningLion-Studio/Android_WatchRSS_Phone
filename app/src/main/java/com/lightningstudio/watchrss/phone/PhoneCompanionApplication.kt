package com.lightningstudio.watchrss.phone

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneCompanionApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val container: PhoneCompanionContainer by lazy {
        PhoneCompanionContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            container.accountRepository.initialize()
            container.usageTelemetry.recordAppLaunch()
            container.repository.recordFirstUseIfAbsent(container.firstInstalledAtMillis)
        }
    }
}
