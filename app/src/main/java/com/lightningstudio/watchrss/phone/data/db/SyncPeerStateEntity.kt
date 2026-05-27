package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_peer_state")
data class SyncPeerStateEntity(
    @PrimaryKey val peerDeviceId: String,
    val lastLocalSeqAckedByPeer: Long = 0L,
    val lastRemoteSeqApplied: Long = 0L,
    val lastFullSyncAt: Long = 0L,
    val lastProtocolVersion: Int = 0,
    val updatedAt: Long = 0L
)
