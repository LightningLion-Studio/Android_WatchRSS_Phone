package com.lightningstudio.watchrss.phone.data.storage

import android.app.Application
import android.content.Context
import com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35],application=Application::class)
class AppStorageManagerTest {
    @Test fun clearingRegenerableWatchCopiesDoesNotDeleteOriginalsOrSignalCloudContentDeletion()=runBlocking {
        val context:Context=RuntimeEnvironment.getApplication()
        val root=context.filesDir
        val original=File(root,"reader_assets/backgrounds/fix3-original.mp4")
        val font=File(root,"reader_assets/fonts/fix3-font.ttf")
        val variant=File(root,"reader_assets/variants/fix3-copy.mp4")
        CloudExportFileWitness.writeBytes(original,"original".toByteArray())
        CloudExportFileWitness.writeBytes(font,"font".toByteArray())
        CloudExportFileWitness.writeBytes(variant,"regenerable copy".toByteArray())
        val before=CloudExportFileWitness.observe(root)
        assertTrue(AppStorageManager(context).clearWatchBackgroundCopies()>0)
        assertEquals("original",original.readText());assertEquals("font",font.readText())
        assertFalse(variant.exists())
        assertEquals(before,CloudExportFileWitness.observe(root))
    }
}
