package com.lightningstudio.watchrss.phone.connection.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioProbeServerTest {
    @Test
    fun `clamps byte range to audio payload`() {
        assertEquals(99, LocalAudioProbeServer.parseRangeStart("bytes=500-", 100))
    }

    @Test
    fun `keeps quality and smooth BLE profiles explicit`() {
        assertEquals(6, BleVideoProfile.QUALITY.targetFps)
        assertEquals("-466-4x3.wvs", BleVideoProfile.QUALITY.assetSuffix)
        assertEquals(12, BleVideoProfile.SMOOTH.targetFps)
        assertEquals("-compact-466-4x3.wvs", BleVideoProfile.SMOOTH.assetSuffix)
    }
}
