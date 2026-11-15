package com.lightningstudio.watchrss.phone.data.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConditionalBodyFileTest {
    @Test fun localWriterCarriesExpectedDigestAcrossStagingAndKeepsWinningOriginal() {
        val root=Files.createTempDirectory("body-cas-").toFile()
        try {
            val target=File(root,"notes/assets/race.png");ConditionalBodyFile.install(target,"before".toByteArray())
            val expected=ConditionalBodyFile.digest(target)
            try {
                ConditionalBodyFile.install(target,"losing edit".toByteArray(),expected) {
                    CloudExportFileWitness.writeBytes(target,"winning edit".toByteArray(),expected)
                }
                fail("stale local writer replaced winner")
            }catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
            assertEquals("winning edit",target.readText());assertTrue(ConditionalBodyFile.conflictRecord(target).isFile)
            val conflict=JSONObject(ConditionalBodyFile.conflictRecord(target).readText());assertEquals(expected,conflict.getString("expectedSha256"))
            ConditionalBodyFile.install(target,"authorized next edit".toByteArray(),ConditionalBodyFile.digest(target))
            assertEquals("authorized next edit",target.readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun expectedAbsentRejectsAConcurrentLocalImportAndPersistsConflict() {
        val root=Files.createTempDirectory("body-cas-").toFile()
        try {
            val target=File(root,"imported_books/test.jsonl")
            try{ConditionalBodyFile.install(target,"remote".toByteArray(),expectedSha=null){ConditionalBodyFile.install(target,"new local".toByteArray())};fail("overwrote local import")}
            catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
            assertEquals("new local",target.readText());assertTrue(ConditionalBodyFile.conflictRecord(target).isFile)
        }finally{root.deleteRecursively()}
    }
}
