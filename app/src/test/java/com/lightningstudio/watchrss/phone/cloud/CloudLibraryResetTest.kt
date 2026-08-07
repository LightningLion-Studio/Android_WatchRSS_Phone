package com.lightningstudio.watchrss.phone.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLibraryResetTest {
    @Test
    fun `destructive reset requires the exact visible phrase`() {
        assertTrue(isCloudLibraryDeleteConfirmed(CLOUD_LIBRARY_DELETE_PHRASE))
        assertFalse(isCloudLibraryDeleteConfirmed("删除云端资料库"))
        assertFalse(isCloudLibraryDeleteConfirmed("$CLOUD_LIBRARY_DELETE_PHRASE "))
    }

    @Test
    fun `missing server route has an actionable in-dialog error`() {
        assertEquals(
            "云端服务尚未启用资料库重置，请稍后重试",
            cloudLibraryResetErrorMessage(IOException("HTTP 404: route not found"))
        )
    }

    @Test
    fun `transport details are not exposed in the dialog`() {
        assertEquals(
            "网络连接失败，请检查网络后重试",
            cloudLibraryResetErrorMessage(IOException("Connection reset by 10.0.0.8"))
        )
    }
}
