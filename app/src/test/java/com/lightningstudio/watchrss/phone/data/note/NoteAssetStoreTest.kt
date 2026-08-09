package com.lightningstudio.watchrss.phone.data.note

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteAssetStoreTest {
    @Test
    fun `landscape image longest edge is reduced to 680 pixels`() {
        assertEquals(680 to 383, scaledImageDimensions(1920, 1080))
    }

    @Test
    fun `portrait image keeps its aspect ratio`() {
        assertEquals(383 to 680, scaledImageDimensions(1080, 1920))
    }

    @Test
    fun `small image is not enlarged`() {
        assertEquals(320 to 240, scaledImageDimensions(320, 240))
    }
}
