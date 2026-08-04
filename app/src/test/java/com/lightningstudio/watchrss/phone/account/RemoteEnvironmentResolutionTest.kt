package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteEnvironmentResolutionTest {
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
