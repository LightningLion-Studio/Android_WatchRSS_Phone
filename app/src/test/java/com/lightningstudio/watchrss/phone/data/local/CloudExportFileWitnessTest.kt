package com.lightningstudio.watchrss.phone.data.local

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudExportFileWitnessTest {
    @Test fun fiveHundredWritesNeedAtMostOneWitnessFsyncPerScopeWindow() {
        val root=Files.createTempDirectory("cloud-witness-500-").toFile()
        try {
            CloudExportFileWitness.revision(root)
            CloudExportFileWitness.resetSyncCounters()
            repeat(500){i->CloudExportFileWitness.changing(File(root,"notes/assets/$i.png")){}}
            val syncs=CloudExportFileWitness.fileSyncs.get()+CloudExportFileWitness.directorySyncs.get()
            println("WITNESS_500_SYNC_COUNT $syncs")
            assertTrue("500 writes require at most one witness fsync; actual=$syncs",syncs<=1)
        } finally {root.deleteRecursively()}
    }
    @Test fun measureBulkTwoThousandImportedTextArticles() {
        val root=Files.createTempDirectory("cloud-witness-2000-").toFile()
        try {
            val store=FileArticleContentStore(File(root,"imported_text"))
            CloudExportFileWitness.revision(root)
            CloudExportFileWitness.resetSyncCounters()
            val start=System.nanoTime()
            repeat(2000){i->store.storeText("article-$i","Article $i\n"+"body text ".repeat(200))}
            val wall=(System.nanoTime()-start)/1_000_000.0
            println("WITNESS_BULK_2000 "+org.json.JSONObject().put("articles",2000).put("wallMs",wall)
                .put("witnessFileFsyncs",CloudExportFileWitness.fileSyncs.get()).put("witnessDirectoryFsyncs",CloudExportFileWitness.directorySyncs.get()))
            assertEquals(2000,File(root,"imported_text").listFiles()!!.count{it.extension=="txt"})
        } finally {root.deleteRecursively()}
    }
    @Test fun checkpointConsumptionStartsTheNextWindowWithoutWitnessIo() {
        val root=Files.createTempDirectory("cloud-witness-windows-").toFile()
        try {
            CloudExportFileWitness.observe(root) // one-time provisioning, outside the window
            repeat(2) {
                CloudExportFileWitness.resetSyncCounters()
                repeat(500){i->CloudExportFileWitness.changing(File(root,"notes/assets/$i.png")){}}
                repeat(500){i->CloudExportFileWitness.changing(File(root,"reader_assets/fonts/$i.ttf")){}}
                assertEquals(2,CloudExportFileWitness.fileSyncs.get())
                assertEquals(0,CloudExportFileWitness.directorySyncs.get())
                val stable=CloudExportFileWitness.observe(root)
                assertTrue(CloudExportFileWitness.complete(root,stable,stable.revision){true})
                assertEquals("consuming a checkpoint uses its existing commit",2,CloudExportFileWitness.fileSyncs.get())
            }
        } finally {root.deleteRecursively()}
    }
    @Test fun laterWritesAndCrashCannotBeAcknowledgedByAnOlderObservationOrOwnHead() {
        val root=Files.createTempDirectory("cloud-witness-crash-").toFile()
        try {
            val before=CloudExportFileWitness.observe(root)
            val target=File(root,"imported_text/late.txt")
            CloudExportFileWitness.changing(target){target.parentFile.mkdirs();target.writeText("first")}
            val exporting=CloudExportFileWitness.observe(root)
            CloudExportFileWitness.resetSyncCounters()
            CloudExportFileWitness.changing(target){target.writeText("late")}
            assertEquals(0,CloudExportFileWitness.fileSyncs.get())
            assertEquals(exporting.revision,CloudExportFileWitness.revision(root))
            assertFalse(CloudExportFileWitness.complete(root,exporting,exporting.revision){fail("stale export acknowledged");true})
            CloudExportFileWitness.forgetProcessForTest(root)
            assertNotEquals(before.revision,CloudExportFileWitness.revision(root))
            assertTrue("a completed own head without checkpoint consumption must not supply old bytes",CloudExportFileWitness.privateFilesPending(root))
            assertEquals("late",target.readText())
            val published=CloudExportFileWitness.observe(root)
            assertTrue(CloudExportFileWitness.complete(root,published,published.revision){true})
            // Crash after receipt persistence but before any later write. First subsequent mutation
            // must get a NEW durable token, even if the previous token remains in the witness file.
            CloudExportFileWitness.forgetProcessForTest(root)
            CloudExportFileWitness.changing(target){target.writeText("after checkpoint")}
            assertNotEquals(published.revision,CloudExportFileWitness.revision(root))
        } finally {root.deleteRecursively()}
    }
    @Test fun rejectedAndAmbiguousCheckpointCommitsKeepThePublicationObligation() {
        val root=Files.createTempDirectory("cloud-witness-commit-").toFile()
        try {
            val target=File(root,"imported_text/a.txt")
            CloudExportFileWitness.changing(target){}
            val observation=CloudExportFileWitness.observe(root)
            assertFalse(CloudExportFileWitness.complete(root,observation,observation.revision){false})
            CloudExportFileWitness.resetSyncCounters()
            CloudExportFileWitness.changing(target){}
            assertEquals(0,CloudExportFileWitness.fileSyncs.get())
            val current=CloudExportFileWitness.observe(root)
            assertTrue(runCatching{CloudExportFileWitness.complete(root,current,current.revision){error("ambiguous commit")}}.isFailure)
            CloudExportFileWitness.changing(target){}
            assertNotEquals(current.revision,CloudExportFileWitness.revision(root))
        } finally {root.deleteRecursively()}
    }
    @Test fun interruptedSlotWritePreservesThePreviousDurableToken() {
        val root=Files.createTempDirectory("cloud-witness-torn-").toFile()
        try {
            CloudExportFileWitness.changing(File(root,"notes/assets/a.png")){}
            val durable=CloudExportFileWitness.revision(root)
            // Generation 1 is in slot 1. Simulate a torn next record in slot 0,
            // before its fsync and before the corresponding payload mutation starts.
            java.io.RandomAccessFile(File(root,".cloud-export-files-v2"),"rw").use{it.seek(0);it.write(ByteArray(128){7});it.fd.sync()}
            CloudExportFileWitness.forgetProcessForTest(root)
            assertEquals(durable,CloudExportFileWitness.revision(root))
        } finally {root.deleteRecursively()}
    }
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
                // A stable durable checkpoint consumes the window; each family then starts a new one.
                assertTrue(CloudExportFileWitness.complete(root,CloudExportFileWitness.observe(root),next){true})
            }
            assertEquals(previous,CloudExportFileWitness.revision(File(root.parentFile,root.name)))
            assertNotEquals(previous,CloudExportFileWitness.revision(Files.createTempDirectory("other-files-").toFile().also{it.deleteOnExit()}))
        } finally {root.deleteRecursively()}
    }
    @Test fun failedWriteKeepsObligationAndAdmissionCannotObserveAnUnfinishedInstallation() {
        val root=Files.createTempDirectory("cloud-file-race-").toFile()
        try {
            val before=CloudExportFileWitness.observe(root)
            assertTrue(runCatching { CloudExportFileWitness.changing(File(root,"notes/assets/failed.png")){error("interrupted write")} }.isFailure)
            assertNotEquals(before.revision,CloudExportFileWitness.revision(root))
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val readStarted=CountDownLatch(1);val readFinished=CountDownLatch(1)
            val target=File(root,"notes/assets/a.png")
            val writer=Thread { CloudExportFileWitness.changing(target) {entered.countDown();check(release.await(5,TimeUnit.SECONDS));target.parentFile.mkdirs();target.writeText("complete")} }
            writer.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
            val reader=Thread {readStarted.countDown();CloudExportFileWitness.revision(root);readFinished.countDown()}
            reader.start();assertTrue(readStarted.await(5,TimeUnit.SECONDS))
            try {assertFalse(readFinished.await(50,TimeUnit.MILLISECONDS))} finally {release.countDown()}
            writer.join(5000);reader.join(5000);assertEquals(0,readFinished.count);assertEquals("complete",target.readText())
            assertFalse(CloudExportFileWitness.complete(root,before,before.revision){true})
        } finally {root.deleteRecursively()}
    }
}
