package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteEnvironmentResolutionTest {
    @Test
    fun testBackendIsDerivedFromProductionBackend() {
        assertEquals(
            "https://sly-data-plane.watchrss.cn/test",
            testBackendBaseUrl("https://sly-data-plane.watchrss.cn/")
        )
    }

    @Test
    fun testBackendStaysEmptyWhenProductionBackendIsMissing() {
        assertEquals("", testBackendBaseUrl("  "))
    }

    @Test
    fun debugBuildUsesPersistedTestEnvironment() {
        assertEquals(
            RemoteEnvironment.TEST,
            resolveRemoteEnvironment(
                isDebugBuild = true,
                persistedValue = RemoteEnvironment.TEST.persistedValue
            )
        )
    }

    @Test
    fun debugBuildDefaultsUnknownValuesToProduction() {
        assertEquals(
            RemoteEnvironment.PRODUCTION,
            resolveRemoteEnvironment(isDebugBuild = true, persistedValue = "unknown")
        )
        assertEquals(
            RemoteEnvironment.PRODUCTION,
            resolveRemoteEnvironment(isDebugBuild = true, persistedValue = null)
        )
    }

    @Test
    fun releaseBuildAlwaysUsesProduction() {
        assertEquals(
            RemoteEnvironment.PRODUCTION,
            resolveRemoteEnvironment(
                isDebugBuild = false,
                persistedValue = RemoteEnvironment.TEST.persistedValue
            )
        )
    }
}
