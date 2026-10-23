package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.note.NoteEntity
import com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec
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

    fun stateHash(note:NoteEntity):String = com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec.sha256(
        com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2.lp("watchrss/note-state/v2",note.noteId,
            if(note.folderId==null)"0" else "1",note.folderId.orEmpty(),note.title,note.contentHash,
            note.baseContentHash,if(note.pinned)"1" else "0",note.createdAt.toString(),note.updatedAt.toString(),
            note.modifiedBy,if(note.deleted)"1" else "0",note.deletedAt.toString()))

    fun summaries(notes:List<NoteEntity>):JSONArray=JSONArray().apply { notes.sortedBy { it.noteId }.forEach { note ->
        put(JSONObject().put("noteId",note.noteId).put("contentHash",note.contentHash).put("updatedAt",note.updatedAt)
            .put("deleted",note.deleted).put("deletedAt",note.deletedAt).put("stateHash",stateHash(note)))
    } }

    internal fun inventory(deviceId:String,notes:List<NoteEntity>,assets:List<NoteAssetInventoryEntry>?):JSONObject=
        JSONObject().put("version",PROTOCOL_VERSION).put("action",ACTION_SYNC_NOTES).put("phase","manifest")
            .put("deviceId",deviceId).put("noteManifest",summaries(notes)).apply {
                assets?.let { put("assetManifest",JSONArray().apply { it.forEach { entry -> put(entry.json()) } }) }
            }

    fun differingNotes(local:List<NoteEntity>,remote:JSONArray):List<NoteEntity> {
        val byId=linkedMapOf<String,JSONObject>()
        for(i in 0 until remote.length()) {
            val row=remote.getJSONObject(i);val id=row.getString("noteId")
            require(id.isNotBlank() && !byId.containsKey(id)) { "笔记清单包含重复或无效身份" }
            require(row.get("contentHash") is String && row.get("deleted") is Boolean)
            for(key in listOf("updatedAt","deletedAt")) {
                val value=row.get(key);require(value is Int || value is Long)
                require((value as Number).toLong() in 0..9_007_199_254_740_991L)
            }
            if(row.has("stateHash"))require(row.get("stateHash") is String && row.getString("stateHash").matches(Regex("[0-9a-f]{64}")))
            byId[id]=row
        }
        return local.filter { note ->
            val row=byId[note.noteId]
            row==null || row.optString("stateHash")!=stateHash(note) || row.optString("contentHash")!=note.contentHash ||
                row.optLong("updatedAt",-1)!=note.updatedAt || row.optBoolean("deleted")!=note.deleted || row.optLong("deletedAt",-1)!=note.deletedAt
        }
    }

    fun delta(deviceId:String,allNotes:List<NoteEntity>,changed:List<NoteEntity>):JSONObject=
        manifest(deviceId,changed).put("phase","delta").put("noteManifest",summaries(allNotes))

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
        markdown = optString("markdown"), plainText = MarkdownNoteCodec.toPlainText(optString("markdown")), contentHash = optString("contentHash"),
        baseContentHash = optString("baseContentHash"), baseMarkdown = optString("baseMarkdown"), pinned = optBoolean("pinned"),
        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt"), modifiedBy = optString("modifiedBy"),
        deleted = optBoolean("deleted"), deletedAt = optLong("deletedAt")
    )
}
