package com.lightningstudio.watchrss.phone.data.reader

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in maintenance invocation; no fixed user resource ID and no automatic migration. */
class AuthorizedReaderBackgroundDeleteDeviceTest {
    @Test fun deleteExplicitlyAuthorizedMissingBackground() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("authorizedBackgroundId")
        val sha = args.getString("authorizedBackgroundSha256")
        assumeTrue("Requires explicit resource ID and SHA authorization", id != null && sha != null)
        require(id!!.matches(Regex("[0-9a-f]{64}")) && sha!!.matches(Regex("[0-9a-f]{64}")))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhoneCompanionApplication
        val repo = app.container.readerPresetRepository
        val before = repo.exportSnapshot()
        val target = before.backgrounds.single { it.id == id }
        assertEquals(sha, target.sha256)
        assertFalse(target.deleted)
        assertNull("This invocation is only for an explicitly unwanted missing original", repo.resourceStore.backgroundFile(target.masterFileName))
        val affected = before.presets.filterNot { it.deleted }.filter {
            val bg = ReaderPresetCodec.decode(it.payloadJson).background
            bg.assetId == id || bg.posterAssetId == id
        }.map { it.id }.toSet()
        repo.deleteBackground(id)
        val after = repo.exportSnapshot()
        assertTrue(after.backgrounds.single { it.id == id }.deleted)
        assertTrue(after.deletions.any { it.kind == "background" && it.entityId == id })
        assertEquals(before.fonts, after.fonts)
        assertEquals(before.backgrounds.filterNot { it.id == id }, after.backgrounds.filterNot { it.id == id })
        assertEquals(before.presets.filterNot { it.id in affected }, after.presets.filterNot { it.id in affected })
        after.presets.filterNot { it.deleted }.forEach {
            val bg = ReaderPresetCodec.decode(it.payloadJson).background
            assertNotEquals(id, bg.assetId); assertNotEquals(id, bg.posterAssetId)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("resourceDeleted", id.take(8)); putInt("themesReset", affected.size)
        })
    }
}
