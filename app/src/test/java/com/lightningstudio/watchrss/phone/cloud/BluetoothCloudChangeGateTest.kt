package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BluetoothCloudChangeGateTest {
    @Test fun longBluetoothOperationDefersAutomaticCloudUntilClosed()=runBlocking<Unit> {
        val gate=BluetoothCloudChangeGate();gate.setActive(true)
        val pending=async(start=CoroutineStart.UNDISPATCHED){gate.awaitAutomaticSync(10)}
        assertFalse(pending.isCompleted)
        gate.setActive(false);assertTrue(pending.await())
    }
    @Test fun completionCallbackConsumesDebounceButDoesNotConsumeLaterLocalChanges()=runBlocking<Unit> {
        val gate=BluetoothCloudChangeGate();gate.setActive(true)
        val pending=async(start=CoroutineStart.UNDISPATCHED){gate.awaitAutomaticSync(10)}
        gate.acknowledge(10);gate.setActive(false)
        assertFalse(pending.await());assertTrue(gate.awaitAutomaticSync(11))
        gate.acknowledge(2);assertFalse(gate.awaitAutomaticSync(10))
    }
    @Test fun failedBluetoothOperationDoesNotDropPendingLocalChanges()=runBlocking<Unit> {
        val gate=BluetoothCloudChangeGate();gate.setActive(true)
        val pending=async(start=CoroutineStart.UNDISPATCHED){gate.awaitAutomaticSync(20)}
        gate.setActive(false);assertTrue(pending.await())
    }
}
