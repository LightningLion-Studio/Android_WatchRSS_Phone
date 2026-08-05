package com.lightningstudio.watchrss.phone.data.note

import org.json.JSONArray
import org.json.JSONObject

object NoteCloudStateCodec {
    private const val FORMAT = "watchrss-notes"
    fun encode(notes: List<NoteEntity>): ByteArray = JSONObject().apply {
        put("format", FORMAT); put("version", 1)
        put("notes", JSONArray().apply { notes.forEach { note -> put(JSONObject().apply {
            put("id", note.noteId); put("folder", note.folderId ?: JSONObject.NULL); put("title", note.title)
            put("markdown", note.markdown); put("hash", note.contentHash); put("base", note.baseMarkdown)
            put("updatedAt", note.updatedAt); put("modifiedBy", note.modifiedBy); put("pinned", note.pinned)
            put("deleted", note.deleted); put("deletedAt", note.deletedAt); put("createdAt", note.createdAt)
        }) } })
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): List<NoteEntity> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == FORMAT && root.optInt("version") == 1) { "云端笔记格式不受支持" }
        val array = root.getJSONArray("notes")
        return List(array.length()) { index -> array.getJSONObject(index).let { json ->
            val markdown = json.optString("markdown")
            NoteEntity(json.getString("id"), if (json.isNull("folder")) null else json.optString("folder"), json.optString("title"), markdown,
                MarkdownNoteCodec.toPlainText(markdown), json.optString("hash"), MarkdownNoteCodec.sha256(json.optString("base")), json.optString("base"),
                json.optBoolean("pinned"), json.optLong("createdAt"), json.optLong("updatedAt"), json.optString("modifiedBy"), json.optBoolean("deleted"), json.optLong("deletedAt"))
        } }
    }
}
