package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import org.json.JSONObject

interface CloudMigrationApi {
    suspend fun capabilities(): JSONObject?
    suspend fun post(path: String, body: JSONObject): JSONObject
    suspend fun get(path: String): JSONObject
    suspend fun publicationProof(jobId:String,batchId:String):JSONObject?
    suspend fun candidateDownload(jobId:String,sha256:String):JSONObject
    suspend fun download(url: String, size: Long, sha256: String): ByteArray
}

class PhoneCloudMigrationApi(private val client: PhoneCloudClient, private val session: () -> PhoneAccountSession,
                             private val checkFence: () -> Unit): CloudMigrationApi {
    override suspend fun capabilities(): JSONObject? { checkFence();return client.formatCapabilities(session()).also { checkFence() } }
    override suspend fun post(path:String,body:JSONObject):JSONObject {
        require(path.startsWith("/cloud/format-migrations"));checkFence()
        val account=session(); val epoch=client.epoch(account)
        return client.v2Post(account,"/functions/v1$path",body,epoch).also { checkFence() }
    }
    override suspend fun get(path:String):JSONObject {
        require(path.startsWith("/cloud/format-migrations"));checkFence()
        return client.v2Get(session(),"/functions/v1$path").also { checkFence() }
    }
    override suspend fun publicationProof(jobId:String,batchId:String):JSONObject? {
        CloudMigrationCrypto.uuid(jobId);CloudMigrationCrypto.uuid(batchId)
        val status=get("/cloud/format-migrations/$jobId")
        val proofs=status.getJSONArray("publications")
        return (0 until proofs.length()).map{proofs.getJSONObject(it)}.singleOrNull{it.getString("batchId")==batchId}
    }
    override suspend fun candidateDownload(jobId:String,sha256:String):JSONObject {
        // Control-plane candidate-reference route is supplied by the shared API contract.
        error("migration_candidate_download_not_configured")
    }
    override suspend fun download(url:String,size:Long,sha256:String):ByteArray { checkFence(); return client.download(url,size,sha256,maximumBytes=size).also { checkFence() } }
}
