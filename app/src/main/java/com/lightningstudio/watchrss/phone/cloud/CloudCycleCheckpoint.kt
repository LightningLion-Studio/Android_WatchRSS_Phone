package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject

/** One durable receipt, written only after all work completes. Absence always means full work. */
internal data class CloudCycleCheckpoint(
    val context:String,
    val heads:Map<String,String>,
    val localMaxSeq:Long,
    val recordsCursor:String,
    val fullCheckedAt:Long
) {
    fun compatible(context:String, cursor:String, now:Long):Boolean = this.context==context &&
        recordsCursor==cursor && fullCheckedAt>0 && now>=fullCheckedAt && now-fullCheckedAt<86_400_000L
    fun plan(current:List<CloudSnapshotHead>, local:Long, recordsEmpty:Boolean):CloudCyclePlan {
        val remoteChanged=heads!=identities(current) || !recordsEmpty
        val localChanged=local!=localMaxSeq
        return when {
            remoteChanged && localChanged -> CloudCyclePlan.FULL
            remoteChanged -> CloudCyclePlan.APPLY
            localChanged -> CloudCyclePlan.PUBLISH
            else -> CloudCyclePlan.IDLE
        }
    }
    fun encode():String = JSONObject().put("version",1).put("context",context).put("heads",JSONObject(heads))
        .put("localMaxSeq",localMaxSeq).put("recordsCursor",recordsCursor).put("fullCheckedAt",fullCheckedAt).toString()
    companion object {
        fun identities(heads:List<CloudSnapshotHead>):Map<String,String> {
            require(heads.map{it.id}.distinct().size==heads.size)
            return heads.associate{it.id to it.manifestSha256}
        }
        fun decode(raw:String?):CloudCycleCheckpoint? = runCatching {
            val j=JSONObject(requireNotNull(raw));require(j.getInt("version")==1)
            val heads=j.getJSONObject("heads")
            CloudCycleCheckpoint(j.getString("context"),heads.keys().asSequence().associateWith{heads.getString(it)},
                j.getLong("localMaxSeq").also{require(it>=0)},j.getString("recordsCursor"),j.getLong("fullCheckedAt"))
        }.getOrNull()
    }
}
internal enum class CloudCyclePlan { FULL, APPLY, PUBLISH, IDLE }
