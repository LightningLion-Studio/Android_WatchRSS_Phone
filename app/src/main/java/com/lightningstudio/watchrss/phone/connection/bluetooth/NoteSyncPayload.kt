package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import org.json.JSONArray
import org.json.JSONObject

/** Separate action so old watch builds can keep syncing the article library unchanged. */
object NoteSyncPayload {
    const val PROTOCOL_VERSION = 1
    const val ACTION_SYNC_NOTES = "syncNotes"

    fun manifest(deviceId: String, notes: List<NoteEntity>): JSONObject = JSONObject().apply {
        put("version", PROTOCOL_VERSION)
        put("action", ACTION_SYNC_NOTES)
        put("deviceId", deviceId)
        put("notes", JSONArray().apply { notes.forEach { put(it.toJson(includeBody = true)) } })
    }

    /**
     * BLE base-station RPC is a different transport from RFCOMM, but it uses the
     * exact same durable note envelope.  Keeping this conversion here prevents
     * the two watch implementations from drifting in their field names.
     */
    fun fromJson(payload: JSONObject): List<NoteEntity> = decodeNotes(payload)

    fun decodeNotes(payload: JSONObject): List<NoteEntity> {
        require(payload.optString("action") == ACTION_SYNC_NOTES) { "不是笔记同步载荷" }
        return payload.optJSONArray("notes")?.let { array ->
            List(array.length()) { index -> array.getJSONObject(index).toNote() }
        }.orEmpty()
    }

    private fun NoteEntity.toJson(includeBody: Boolean): JSONObject = JSONObject().apply {
        put("noteId", noteId); put("folderId", folderId ?: JSONObject.NULL); put("title", title)
        put("contentHash", contentHash); put("updatedAt", updatedAt); put("modifiedBy", modifiedBy)
        put("deleted", deleted); put("deletedAt", deletedAt)
        if (includeBody) { put("markdown", markdown); put("baseContentHash", baseContentHash); put("baseMarkdown", baseMarkdown); put("pinned", pinned); put("createdAt", createdAt) }
    }

    private fun JSONObject.toNote(): NoteEntity = NoteEntity(
        noteId = getString("noteId"), folderId = if (isNull("folderId")) null else optString("folderId"), title = optString("title"),
        markdown = optString("markdown"), plainText = "", contentHash = optString("contentHash"),
        baseContentHash = optString("baseContentHash"), baseMarkdown = optString("baseMarkdown"), pinned = optBoolean("pinned"),
        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt"), modifiedBy = optString("modifiedBy"),
        deleted = optBoolean("deleted"), deletedAt = optLong("deletedAt")
    )
}
