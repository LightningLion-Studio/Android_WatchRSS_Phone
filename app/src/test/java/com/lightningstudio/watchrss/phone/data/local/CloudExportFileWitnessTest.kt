package com.lightningstudio.watchrss.phone.data.local

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudExportFileWitnessTest {
    @Test fun allExportedFileFamiliesLeaveADurableWitnessWithoutAnyRoomWrites() {
        val root=Files.createTempDirectory("cloud-file-witness-").toFile()
        try {
            var previous=CloudExportFileWitness.revision(root)
            for(path in listOf("notes/assets/a.png","reader_assets/fonts/a.ttf","reader_assets/backgrounds/a.png","reader_assets/variants/a.webp","imported_books/a.jsonl","imported_text/a.txt")) {
                CloudExportFileWitness.writeBytes(File(root,path),"new bytes".toByteArray())
                val next=CloudExportFileWitness.revision(root)
                assertNotEquals(path,previous,next)
                if(path.startsWith("notes/"))assertEquals(previous.privateFiles,next.privateFiles)
                else assertEquals(previous.notes,next.notes)
                previous=next
            }
            assertEquals(previous,CloudExportFileWitness.revision(File(root.parentFile,root.name)))
            assertNotEquals(previous,CloudExportFileWitness.revision(Files.createTempDirectory("other-files-").toFile().also{it.deleteOnExit()}))
        } finally {root.deleteRecursively()}
    }
    @Test fun failedWriteKeepsObligationAndAdmissionCannotObserveAnUnfinishedInstallation() {
        val root=Files.createTempDirectory("cloud-file-race-").toFile()
        try {
            val before=CloudExportFileWitness.revision(root)
            assertTrue(runCatching { CloudExportFileWitness.changing(File(root,"notes/assets/failed.png")){error("interrupted write")} }.isFailure)
            assertNotEquals(before,CloudExportFileWitness.revision(root))
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val readStarted=CountDownLatch(1);val readFinished=CountDownLatch(1)
            val target=File(root,"notes/assets/a.png")
            val writer=Thread { CloudExportFileWitness.changing(target) {entered.countDown();check(release.await(5,TimeUnit.SECONDS));target.parentFile.mkdirs();target.writeText("complete")} }
            writer.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
            val reader=Thread {readStarted.countDown();CloudExportFileWitness.revision(root);readFinished.countDown()}
            reader.start();assertTrue(readStarted.await(5,TimeUnit.SECONDS))
            try {assertFalse(readFinished.await(50,TimeUnit.MILLISECONDS))} finally {release.countDown()}
            writer.join(5000);reader.join(5000);assertEquals(0,readFinished.count);assertEquals("complete",target.readText())
            assertNull(CloudExportFileWitness.withRevision(root,before){"stale receipt"})
        } finally {root.deleteRecursively()}
    }
}
