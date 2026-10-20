package com.lightningstudio.watchrss.phone.connection.bluetooth

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only production companion discovery of the current PodJS watch. */
class PhonePodjsProbeDeviceTest {
    @Test fun discoversPairedPodjsWatch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val expectedAddress = requireNotNull(args.getString("watchAddress"))
        val expectedId = requireNotNull(args.getString("watchDeviceId"))
        val app = instrumentation.targetContext.applicationContext as PhoneCompanionApplication
        val targets = app.container.bluetoothSyncManager.probeLibrarySyncTargets()
        try {
            val watch = targets.devices.singleOrNull { it.address.equals(expectedAddress, ignoreCase = true) }
            assertTrue("Production phone discovery did not find the PodJS watch", watch != null)
            assertEquals(expectedId, watch!!.remoteDeviceId)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("podjsProbeAddress", watch.address)
                putString("podjsProbeDeviceId", watch.remoteDeviceId)
                putBoolean("podjsProbePersistent", watch.supportsPersistentSession)
            })
        } finally {
            targets.sessionLease?.close()
        }
    }
}
