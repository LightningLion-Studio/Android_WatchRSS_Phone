package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.account.RemoteEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityEnvironmentSwitchTest {
    @Test
    fun productionSwitchOnlyAppearsForDebugTestEnvironment() {
        assertTrue(shouldShowProductionEnvironmentSwitch(true, RemoteEnvironment.TEST))
        assertFalse(shouldShowProductionEnvironmentSwitch(true, RemoteEnvironment.PRODUCTION))
        assertFalse(shouldShowProductionEnvironmentSwitch(false, RemoteEnvironment.TEST))
    }
}
