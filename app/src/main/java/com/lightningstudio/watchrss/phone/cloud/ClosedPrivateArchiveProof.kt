package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness
import org.json.JSONObject

/** Durable scoped fact from a successful checkpoint, independent of a later cycle's invalidation.
 * It certifies one authenticated own head at a consumed token, never an arbitrary head/token pair.
 */
internal data class ClosedPrivateArchiveProof(
    val owner:CloudSnapshotReuse.Owner,
    val headId:String,
    val manifestSha256:String,
    val token:String
) {
    fun proves(manifest:CloudSnapshotManifest,currentOwner:CloudSnapshotReuse.Owner,currentToken:String,headSha256:String?):Boolean =
        owner==currentOwner && owner.current(manifest) && headId==manifest.snapshotId && manifestSha256==headSha256 && token==currentToken &&
            manifest.settings.optString("privateFilesRevision","")==token &&
            CloudExportFileWitness.PrivateObservation.decode(manifest.settings.optJSONObject(CloudSnapshotReuse.PRIVATE_FILES_OBSERVATION))?.token==token

    fun encode():String = owner.settings().put("version",1).put("closed",true).put("deviceId",owner.deviceId)
        .put("headId",headId).put("manifestSha256",manifestSha256).put("token",token).toString()
    companion object {
        fun decode(raw:String?):ClosedPrivateArchiveProof?=runCatching {
            val row=JSONObject(requireNotNull(raw));require(row.getInt("version")==1 && row.getBoolean("closed"))
            val owner=CloudSnapshotReuse.Owner(row.getString("deviceId"),row.getString("ownerUserId"),row.getString("libraryEpoch"),row.getString("backendScope"),row.getString("contentGeneration"))
            require(listOf(owner.deviceId,owner.userId,owner.epoch,owner.backendScope,owner.generation).all{it.isNotBlank()})
            val headId=row.getString("headId").also{require(it.isNotBlank())}
            val sha=row.getString("manifestSha256").also{require(it.matches(Regex("[0-9a-f]{64}")))}
            val token=row.getString("token").also{require(it=="0" || java.util.UUID.fromString(it).toString()==it)}
            ClosedPrivateArchiveProof(owner,headId,sha,token)
        }.getOrNull()
    }
}
