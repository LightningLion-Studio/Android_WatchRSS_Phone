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
}
