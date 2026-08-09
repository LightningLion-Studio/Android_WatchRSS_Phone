package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSyncPayloadTest {
    @Test
    fun decodedWatchNote_rebuildsPlainTextForPhoneListAndSearch() {
        val markdown = "# 标题\n\n**正文**"
        val response = JSONObject().apply {
            put("action", NoteSyncPayload.ACTION_SYNC_NOTES)
            put("notes", JSONArray().put(JSONObject().apply {
                put("noteId", "note-1")
                put("folderId", JSONObject.NULL)
                put("title", "标题")
                put("markdown", markdown)
                put("contentHash", "hash")
                put("baseContentHash", "hash")
                put("baseMarkdown", markdown)
                put("pinned", false)
                put("createdAt", 1L)
                put("updatedAt", 2L)
                put("modifiedBy", "watch")
                put("deleted", false)
                put("deletedAt", 0L)
            }))
        }

        val decoded = NoteSyncPayload.decodeNotes(response).single()

        assertEquals(MarkdownNoteCodec.toPlainText(markdown), decoded.plainText)
    }
}
