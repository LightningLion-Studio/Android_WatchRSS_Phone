package com.lightningstudio.watchrss.phone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncPeerStateDao {
    @Query("SELECT * FROM sync_peer_state WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun get(peerDeviceId: String): SyncPeerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncPeerStateEntity)
}
