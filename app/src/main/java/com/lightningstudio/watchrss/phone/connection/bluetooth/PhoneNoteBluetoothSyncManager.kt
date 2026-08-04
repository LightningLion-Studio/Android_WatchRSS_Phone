package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.data.note.NoteRepository

class PhoneNoteBluetoothSyncManager(
    private val repository: NoteRepository,
    private val client: PhoneBluetoothSyncClient,
    private val deviceId: String
) {
    suspend fun sync(): Int {
        val exchange = client.exchange(NoteSyncPayload.manifest(deviceId, repository.allNotes()))
        require(exchange.response.optBoolean("success")) {
            exchange.response.optString("message").ifBlank { "手表笔记同步失败" }
        }
        require(exchange.response.optString("action") == NoteSyncPayload.ACTION_SYNC_NOTES) { "手表不支持笔记同步，请升级手表端" }
        val remoteDeviceId = exchange.response.optString("deviceId").ifBlank { "watch" }
        NoteSyncPayload.decodeNotes(exchange.response).forEach { repository.applyRemote(it, remoteDeviceId) }
        return exchange.response.optInt("applied")
    }
}
