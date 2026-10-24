package com.lightningstudio.watchrss.phone.cloud
import kotlinx.coroutines.Job
import org.junit.Test
import org.junit.Assert.*
class BluetoothAutomaticCloudFenceTest {
 @Test fun unchangedRoundDoesNotCreateCloudWork(){val f=BluetoothAutomaticCloudFence();f.setActive(true);f.setActive(false);assertFalse(f.needsResume)}
 @Test fun runningAutomaticJobIsCancelledAndRetainedUntilAnActualRunStarts(){val f=BluetoothAutomaticCloudFence();val old=Job();f.started(false,old);f.setActive(true);assertTrue(old.isCancelled);assertFalse(f.needsResume);f.finished(old);f.setActive(false);assertTrue(f.needsResume);f.setActive(true);f.setActive(false);assertTrue(f.needsResume);f.started(false,Job());assertFalse(f.needsResume)}
 @Test fun explicitManualSyncIsNotCancelledByBluetooth(){val f=BluetoothAutomaticCloudFence();val manual=Job();f.started(true,manual);f.setActive(true);assertTrue(manual.isActive);f.setActive(false);assertFalse(f.needsResume)}
 @Test fun raceWhereBluetoothStartsBeforeAutomaticRegistrationStillDefers(){val f=BluetoothAutomaticCloudFence();f.setActive(true);val job=Job();f.started(false,job);assertTrue(job.isCancelled);f.finished(job);f.setActive(false);assertTrue(f.needsResume)}
}
