package com.lightningstudio.watchrss.phone.data.book

import org.json.*
import org.junit.Test
import org.junit.Assert.*

class BookManifestValidationTest {
    private fun state():JSONObject {
        val fp=BookSync.fingerprint(listOf(BookSync.chapterHash(1,"同名章节","正文")))
        return JSONObject().put("bookId",BookSync.id(fp)).put("fingerprint",fp).put("chapterCount",1)
            .put("title","书名").put("sourceDeviceId","watch").put("deleted",false)
            .put("deletionClock",0).put("deletedAt",0).put("updatedAt",1).put("deletedChapters",JSONArray())
    }
    private fun rejection(value:JSONObject,expected:String) {
        val error=assertThrows(InvalidBookPackageException::class.java){validateBookPackage{BookSync.validate(value)}}
        assertEquals(expected,error.reason)
        assertEquals("book_package_invalid",error.message)
    }
    @Test fun everyManifestFieldHasAStableValueFreeReason() {
        rejection(state().put("fingerprint","bad"),"manifest_fingerprint_invalid")
        rejection(state().put("bookId","bad"),"manifest_book_id_invalid")
        rejection(state().put("bookId","a".repeat(64)),"manifest_identity_mismatch")
        for((field,reason) in listOf("title" to "title","sourceDeviceId" to "source_device_id","deleted" to "deleted","sourceUrl" to "source_url","kind" to "kind")) {
            rejection(state().put(field,JSONObject.NULL),"manifest_"+reason+"_type")
        }
        for((field,reason) in listOf("chapterCount" to "chapter_count","deletionClock" to "deletion_clock","deletedAt" to "deleted_at","updatedAt" to "updated_at")) {
            rejection(state().put(field,1.5),"manifest_"+reason+"_type")
            rejection(state().put(field,-1),"manifest_"+reason+"_range")
            rejection(state().put(field,9_007_199_254_740_992L),"manifest_"+reason+"_range")
            rejection(state().apply{remove(field)},"manifest_"+reason+"_type")
        }
        for(count in listOf(0,100001))rejection(state().put("chapterCount",count),"manifest_chapter_count_range")
        rejection(state().put("deletedChapters","bad"),"manifest_deleted_chapters_type")
        rejection(state().put("deletedChapters",JSONArray().put(1.5)),"manifest_deleted_chapter_type")
        for(index in listOf(0,2))rejection(state().put("deletedChapters",JSONArray().put(index)),"manifest_deleted_chapter_range")
        rejection(state().put("deletedChapters",JSONArray(listOf(1,1))),"manifest_deleted_chapter_order")
        rejection(state().put("chapterCount",2).put("deletedChapters",JSONArray(listOf(2,1))),"manifest_deleted_chapter_order")
    }
    @Test fun emptyAndLongTitlesDeletedChaptersAndUnknownVersionAreNotManifestFailures() {
        for(title in listOf("","标题".repeat(10000),"\uFEFF标题","同名章节"))BookSync.validate(state().put("title",title))
        BookSync.validate(state().put("chapterCount",100000).put("deletedChapters",JSONArray(listOf(1,100000))))
        BookSync.validate(state().put("deleted",true).put("deletedAt",10).put("version",999))
        BookSync.validate(state().put("sourceUrl","").put("kind","unknown"))
    }
    @Test fun watchInstanceIdentityIsDiagnosedWithoutRelaxingTheLegacyIdentityCheck() {
        val state=state();val instance="1".repeat(32)
        val id=com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(
            com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2.lp("watchrss/book/instance-id/v1",state.getString("fingerprint"),instance))
        rejection(state.put("bookId",id).put("instanceId",instance),"manifest_instance_identity_unsupported")
        rejection(state.put("bookId","a".repeat(64)),"manifest_identity_mismatch")
    }
    @Test fun localCountConflictHasItsOwnReasonAndDoesNotChangeTheLocalState() {
        val local=state();val remote=JSONObject(local.toString()).put("chapterCount",2)
        val before=local.toString()
        val error=assertThrows(InvalidBookPackageException::class.java){BookSync.merge(local,remote)}
        assertEquals("manifest_local_identity_conflict",error.reason);assertEquals(before,local.toString())
    }

    private fun lifecycle(generation: Int) = JSONObject()
        .put("generation", generation).put("operationId", "b".repeat(32)).put("startedAt", generation)

    private fun tombstone(updatedAt: Int) = state().put("deleted", true)
        .put("updatedAt", updatedAt).put("deletedAt", updatedAt).put("deletionClock", updatedAt)

    @Test fun v1MergeAppliesNewerLegacyTombstoneWithExactReleaseLineState() {
        val local = state().put("lifecycle", lifecycle(1))
        val remote = tombstone(2)

        assertEquals(BookSync.canonical(remote), BookSync.canonical(BookSync.merge(local, remote)))
    }

    @Test fun b2MergeImportsNewerLegacyTombstoneAndFencesOldLifecycleReplay() {
        val local = state().put("lifecycle", lifecycle(1))
        val remote = tombstone(2)
        val merged = BookSync.merge(local, remote, binary = true)
        val expected = BookSync.canonical(tombstone(2).put("lifecycle", lifecycle(1)))

        assertEquals(expected, BookSync.canonical(merged))
        assertEquals(expected, BookSync.canonical(BookSync.merge(remote, local, binary = true)))
        assertEquals(expected, BookSync.canonical(BookSync.merge(merged, local, binary = true)))

        val laterGeneration = state().put("lifecycle", lifecycle(2)).put("updatedAt", 3)
        assertEquals(BookSync.canonical(laterGeneration),
            BookSync.canonical(BookSync.merge(merged, laterGeneration, binary = true)))
    }

    @Test fun b2LifecycleRejectsStaleLegacyAndExplicitOlderGenerationTombstones() {
        val local = state().put("lifecycle", lifecycle(2)).put("updatedAt", 3)
        val expected = BookSync.canonical(local)
        val staleLegacy = tombstone(2)
        val oldGeneration = tombstone(4).put("lifecycle", lifecycle(1))

        assertEquals(expected, BookSync.canonical(BookSync.merge(local, staleLegacy, binary = true)))
        assertEquals(expected, BookSync.canonical(BookSync.merge(local, oldGeneration, binary = true)))
    }

    @Test fun legacyChapterTombstoneSurvivesBothRoutesAndTitleReplay() {
        val local = state().put("lifecycle", lifecycle(1))
        val legacy = state().put("updatedAt", 2).put("deletionClock", 2)
            .put("deletedChapters", JSONArray().put(1))
        val edit = state().put("lifecycle", lifecycle(1)).put("updatedAt", 3).put("title", "edited")
        for (binary in listOf(false, true)) {
            val deleted = BookSync.merge(local, legacy, binary)
            val replayed = BookSync.merge(deleted, edit, binary = true)
            assertEquals(setOf(1), BookSync.deletions(replayed))
            assertEquals(2, replayed.getInt("deletionClock"))
            assertEquals("edited", replayed.getString("title"))
        }
        val next = state().put("lifecycle", lifecycle(2)).put("updatedAt", 3)
        assertEquals(emptySet<Int>(), BookSync.deletions(BookSync.merge(legacy, next, binary = true)))
    }

    @Test fun legacyWholeBookDeletionSurvivesCrossRouteReplay() {
        val local = state().put("lifecycle", lifecycle(1))
        val legacy = tombstone(2)
        val edit = state().put("lifecycle", lifecycle(1)).put("updatedAt", 3).put("title", "edited")
        val fromV1 = BookSync.merge(local, legacy)
        val fromB2 = BookSync.merge(local, legacy, binary = true)
        assertEquals(
            BookSync.canonical(BookSync.merge(fromB2, edit, binary = true)),
            BookSync.canonical(BookSync.merge(fromV1, edit, binary = true))
        )
        assertEquals(true, BookSync.merge(edit, fromV1, binary = true).getBoolean("deleted"))
    }

    @Test fun legacyWholeAndChapterTombstonesHaveIndependentGenerationBoundaries() {
        val current = state().put("lifecycle", lifecycle(2).put("startedAt", 10)).put("updatedAt", 10)
        val legacy = tombstone(2).put("updatedAt", 12).put("deletionClock", 12)
            .put("deletedChapters", JSONArray().put(1))
        val chapterOnly = BookSync.merge(current, legacy, binary = true)
        assertFalse(chapterOnly.getBoolean("deleted"))
        assertEquals(0L, chapterOnly.getLong("deletedAt"))
        assertEquals(setOf(1), BookSync.deletions(chapterOnly))
        assertEquals(12L, chapterOnly.getLong("deletionClock"))
        assertEquals(BookSync.canonical(chapterOnly),
            BookSync.canonical(BookSync.merge(legacy, current, binary = true)))

        val wholeOnly = BookSync.merge(current, legacy.put("deletedAt", 12).put("deletionClock", 10), binary = true)
        assertTrue(wholeOnly.getBoolean("deleted"))
        assertEquals(12L, wholeOnly.getLong("deletedAt"))
        assertTrue(BookSync.deletions(wholeOnly).isEmpty())
        assertEquals(0L, wholeOnly.getLong("deletionClock"))
    }

    @Test fun b2OnlyInstanceRequiresItsLifecycleForDeletion() {
        val instance = "a".repeat(32)
        val local = state().put("lifecycle", lifecycle(1)).put("instanceId", instance)
        val id = BookSync.binaryId(local.getString("fingerprint"), instance)
        local.put("bookId", id)
        val remote = tombstone(2).put("instanceId", instance).put("bookId", id)

        assertEquals(BookSync.canonical(local), BookSync.canonical(BookSync.merge(local, remote, binary = true)))
    }
}
