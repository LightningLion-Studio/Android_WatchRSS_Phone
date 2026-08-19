package com.lightningstudio.watchrss.phone.data.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseOobeDownloadTelemetryTest {
    @Test
    fun `only release oobe screens wider than 720 pixels count`() {
        assertFalse(shouldCountReleaseOobeOpen(debugBuild = true, screenWidthPixels = 1080))
        assertFalse(shouldCountReleaseOobeOpen(debugBuild = false, screenWidthPixels = 720))
        assertTrue(shouldCountReleaseOobeOpen(debugBuild = false, screenWidthPixels = 721))
    }
}
