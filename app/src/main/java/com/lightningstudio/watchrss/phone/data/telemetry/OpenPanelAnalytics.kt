package com.lightningstudio.watchrss.phone.data.telemetry

import android.content.Context
import android.os.Build
import com.dev.openpanelsdk.OpenPanel
import com.lightningstudio.watchrss.phone.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OpenPanelAnalytics(
    private val context: Context,
    private val appScope: CoroutineScope
) {
    private val openPanel: OpenPanel? by lazy {
        val clientId = BuildConfig.WATCHRSS_OPENPANEL_CLIENT_ID
        if (clientId.isBlank()) return@lazy null
        val apiUrl = BuildConfig.WATCHRSS_OPENPANEL_API_URL
        OpenPanel.create(
            context = context.applicationContext,
            options = OpenPanel.Options(
                clientId = clientId,
                clientSecret = BuildConfig.WATCHRSS_OPENPANEL_CLIENT_SECRET.takeIf { it.isNotBlank() },
                apiUrl = apiUrl.takeIf { it.isNotBlank() } ?: "https://api.openpanel.dev",
                waitForProfile = true,
                verbose = BuildConfig.DEBUG
            )
        )
    }

    private val isConfigured: Boolean
        get() = BuildConfig.WATCHRSS_OPENPANEL_CLIENT_ID.isNotBlank()

    private val propertiesQueue = mutableListOf<() -> Unit>()
    private var identifiedProfileId: String? = null

    fun setGlobalProperties(properties: Map<String, Any>) {
        if (!isConfigured) return
        enqueue {
            openPanel?.setGlobalProperties(properties)
        }
    }

    fun identify(profileId: String, traits: Map<String, Any>? = null) {
        if (!isConfigured) return
        identifiedProfileId = profileId
        enqueue {
            openPanel?.identify(profileId, traits)
        }
    }

    fun track(event: String, properties: Map<String, Any>? = null) {
        if (!isConfigured) return
        enqueue {
            openPanel?.track(event, properties)
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (openPanel == null) {
            propertiesQueue.add(action)
            return
        }
        flushQueue()
        appScope.launch(Dispatchers.IO) {
            action()
        }
    }

    private fun flushQueue() {
        val actions = propertiesQueue.toList()
        propertiesQueue.clear()
        if (actions.isEmpty()) return
        appScope.launch(Dispatchers.IO) {
            actions.forEach { it() }
        }
    }

    fun flush() {
        if (!isConfigured) return
        appScope.launch(Dispatchers.IO) {
            openPanel?.flush()
        }
    }
}