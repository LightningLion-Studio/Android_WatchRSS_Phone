package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only production companion debug-log pull from the paired PodJS watch. */
class PhonePodjsDebugLogDeviceTest {
    @Test fun collectsBoundedWatchLog() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val expectedAddress = requireNotNull(args.getString("watchAddress"))
        val expectedId = requireNotNull(args.getString("watchDeviceId"))
        val app = instrumentation.targetContext.applicationContext as PhoneCompanionApplication
        val manager = app.container.bluetoothSyncManager
        val targets = manager.probeLibrarySyncTargets()
        try {
            val watch = targets.devices.singleOrNull { it.address.equals(expectedAddress, ignoreCase = true) }
            assertTrue("Expected paired PodJS watch was not found", watch != null)
            assertEquals(expectedId, watch!!.remoteDeviceId)
        } finally {
            targets.sessionLease?.close()
        }
        val result = manager.collectWatchDebugLog()
        assertTrue("Watch log has no sync timeline", result.text.contains("bluetooth state="))
        assertFalse("Watch log exposed a request payload", result.text.contains("syncAccount token="))
        assertTrue("Watch log exceeded the phone's request bound", result.text.length <= 700_000)
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("podjsDebugLogChars", result.text.length)
            putBoolean("podjsDebugLogTruncated", result.truncated)
        })
    }
}
