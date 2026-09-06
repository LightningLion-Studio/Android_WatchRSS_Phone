package com.lightningstudio.watchrss.phone.data.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchBackgroundSizeTest {
    @Test fun fullLandscapeAndPortraitFitDoubleDisplayBounds() {
        assertEquals(WatchBackgroundSize(800, 450), watchBackgroundSize(1920, 1080, 400, 500))
        assertEquals(WatchBackgroundSize(562, 1000), watchBackgroundSize(1080, 1920, 400, 500))
        assertEquals(WatchBackgroundSize(800, 100), watchBackgroundSize(4000, 500, 400, 500))
    }
    @Test fun smallSourcesAreNeverUpscaled() {
        assertEquals(WatchBackgroundSize(101, 77), watchBackgroundSize(101, 77, 400, 500))
        assertEquals(WatchBackgroundSize(100, 76), watchBackgroundSize(101, 77, 400, 500, video = true))
    }
    @Test fun videoRotationPrecedesSizingAndDimensionsAreEven() {
        assertEquals(WatchBackgroundSize(562, 1000), watchBackgroundSize(1920, 1080, 400, 500, 90, true))
        assertEquals(WatchBackgroundSize(562, 1000), watchBackgroundSize(1920, 1080, 400, 500, 270, true))
        assertEquals(WatchBackgroundSize(800, 450), watchBackgroundSize(1920, 1080, 400, 500, 180, true))
    }
    @Test(expected = IllegalArgumentException::class) fun missingCapabilitiesAreRejected() {
        watchBackgroundSize(1920, 1080, 0, 0)
    }
    @Test(expected = IllegalArgumentException::class) fun unencodableSliversAreRejectedInsteadOfUpscaled() {
        watchBackgroundSize(10000, 2, 400, 500, video = true)
    }
}
