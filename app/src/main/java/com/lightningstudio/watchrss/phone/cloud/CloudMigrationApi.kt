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
        return client.migrationGet(session(),"/functions/v1$path").also { checkFence() }
    }
    override suspend fun publicationProof(jobId:String,batchId:String):JSONObject? {
        CloudMigrationCrypto.uuid(jobId);CloudMigrationCrypto.uuid(batchId)
        return try { get("/cloud/format-migrations/$jobId/publications/$batchId").also {
            require(it.getString("batchId")==batchId)
        } } catch(error:CloudHttpException) {
            if(error.status==404 && cloudErrorCode(error)=="migration_publication_not_found")null else throw error
        }
    }
    override suspend fun candidateDownload(jobId:String,sha256:String):JSONObject {
        CloudMigrationCrypto.uuid(jobId);CloudMigrationCrypto.hash(sha256)
        return get("/cloud/format-migrations/$jobId/chunks/$sha256").also {
            require(it.getString("jobId")==jobId && it.getString("sha256")==sha256)
            CloudMigrationCrypto.hash(it.getString("planHash"));require(CloudMigrationCrypto.integer(it,"sizeBytes")>0)
            require(CloudMigrationCrypto.integer(it,"expiresAt")>0 && it.getString("downloadRef").isNotBlank())
        }
    }

    override suspend fun download(url:String,size:Long,sha256:String):ByteArray { checkFence(); return client.download(url,size,sha256,maximumBytes=size).also { checkFence() } }
}
