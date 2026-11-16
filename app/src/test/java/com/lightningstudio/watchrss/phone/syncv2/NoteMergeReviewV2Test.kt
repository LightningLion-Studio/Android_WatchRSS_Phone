package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import com.lightningstudio.watchrss.phone.data.note.noteConflictId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NoteMergeReviewV2Test {
    @Test fun RVM07_firstV2ImportDeduplicatesAliasesBeforeLegacyUiReadsThem() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            PhoneCompanionDatabase::class.java).allowMainThreadQueries().build()
        try {
            val tuple = listOf("n", "a", "b", "c", "peer")
            val old = MarkdownNoteCodec.sha256(tuple.joinToString("\u0000"))
            val fresh = noteConflictId("n", "a", "b", "c", "peer")
            fun conflict(id: String, resolved: Int) = JSONObject().put("conflictId", id).put("noteId", "n")
                .put("baseMarkdown", "a").put("localMarkdown", "b").put("remoteMarkdown", "c")
                .put("remoteDeviceId", "peer").put("createdAt", 1).put("resolvedAt", resolved)
            val aliases = listOf(conflict(old, 0), conflict(fresh, 50)).sortedBy { it.getString("conflictId") }
            val set = JSONObject().put("version", 1).put("records", JSONArray(aliases))
            val value = JSONObject().put("noteId", "n").put("folderId", JSONObject.NULL).put("title", "T")
                .put("markdown", "c").put("plainText", "c").put("contentHash", MarkdownNoteCodec.sha256("c"))
                .put("baseMarkdown", "c").put("baseContentHash", MarkdownNoteCodec.sha256("c"))
                .put("pinned", false).put("createdAt", 1).put("updatedAt", 100)
                .put("modifiedBy", "peer").put("deleted", false).put("deletedAt", 0).put("noteConflicts", set)
            val pair = SyncV2Business.split("note", "n", "0".repeat(64), value, null)
            val record = JSONObject().put("meta", pair.meta).put("state", pair.state).put("value", value)
            val sql = db.openHelper.writableDatabase
            val apply = SyncV2PhoneApply(sql)
            // Cover first import, explicit replacement, and the final SQL entry point.
            for (mode in listOf("first", "replace", "direct")) {
            val effective = if(mode=="direct") listOf(record) else apply.effective(emptyList(), listOf(record), mode=="replace")
            sql.beginTransaction()
            try { apply.apply(emptyList(), effective); sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            val stored = db.noteDao().conflictsFor("n")
            assertTrue("RVM-07: v2 import reopened an incident already resolved under its new alias", stored.all { it.resolvedAt > 0 })
            assertEquals(listOf(old), stored.map { it.conflictId })
            }
        } finally { db.close() }
    }
}
