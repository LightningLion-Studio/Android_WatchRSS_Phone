package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Frozen, locally authenticated input. No account or chunk decryption keys are retained here. */
class CloudMigrationInput internal constructor(
    val snapshotId: String, val manifestSha256: String, val descriptor: CloudObjectDescriptor,
    val semanticSha256: String, val type: String, val categories: Set<String>, val appMetaCheckedSafe: Boolean, val keyVersion:Int = 1
) {
    val descriptorSha256: String get() = CloudSnapshotCodec.sha256(CloudMigrationJcs.bytes(descriptor.toJson()))
    override fun toString() = "CloudMigrationInput(redacted)"
}

class CloudMigrationDraft internal constructor(val plan: JSONObject, val inputs: List<CloudMigrationInput>, val details: JSONObject) {
    val planHash: String get() = CloudMigrationJcs.hash(plan)
    override fun toString() = "CloudMigrationDraft(redacted)"
}

object CloudMigrationPlanBuilder {
    val types = setOf("private-article-body", "private-book", "reader-resource", "reader-config", "notes-body", "notes-attachment")
    fun create(userId: String, installationId: String, epoch: String, generation: String, transform: JSONObject,
               inputs: List<CloudMigrationInput>, now: Long, mode: String = "sample", expiresAt: Long = now + 86_400_000,
               planId: String = java.util.UUID.randomUUID().toString()): CloudMigrationDraft {
        CloudMigrationCrypto.uuid(planId); CloudMigrationCrypto.uuid(epoch); CloudMigrationCrypto.uuid(generation)
        require(userId.isNotBlank() && installationId.isNotBlank() && inputs.isNotEmpty())
        require(mode in setOf("sample","full") && expiresAt - now in 1..259_200_000)
        require(inputs.map { it.descriptor.name }.distinct().size == inputs.size)
        CloudMigrationCrypto.exact(transform,"id","version","codeSha256")
        require(transform.getString("id") == "identity-repack" && CloudMigrationCrypto.integer(transform,"version") == 1L)
        CloudMigrationCrypto.hash(transform.getString("codeSha256"))
        inputs.forEach {
            require(it.type in types && it.categories.isNotEmpty() && it.categories.none { category -> category.startsWith("record") }) { "migration_not_suitable" }
            require(it.appMetaCheckedSafe) { "migration_not_suitable" }
            // The registered worker accepts only this closed body descriptor. Preserve registered
            // business metadata/unknown tokens locally; never strip them to make a grant fit.
            val descriptor=it.descriptor.toJson()
            val supported=runCatching {
                descriptor.keys().asSequence().all{key->key in setOf("name","encoding","originalBytes","encodedBytes","chunks","features")} &&
                    (!descriptor.has("features") || descriptor.getJSONObject("features").let { f ->
                        f.keys().asSequence().all{key->key in setOf("incompat","roCompat")} &&
                            listOf("incompat","roCompat").all{key->!f.has(key) || f.getJSONArray(key).length()==0}
                    })
            }.getOrDefault(false)
            require(supported) { "migration_not_suitable" }
            require(it.descriptor.entryRef == null && it.descriptor.access == CloudFormatAccess.WRITABLE && it.descriptor.chunks.isNotEmpty())
            CloudMigrationCrypto.uuid(it.snapshotId); CloudMigrationCrypto.hash(it.manifestSha256); CloudMigrationCrypto.hash(it.semanticSha256)
        }
        val sortedInputs=inputs.sortedBy { it.descriptorSha256 }
        require(sortedInputs.map{it.descriptorSha256}.distinct().size==inputs.size)
        val details=JSONObject().put("objects",JSONArray(sortedInputs.map { input -> JSONObject()
            .put("inputDescriptor",input.descriptor.toJson()).put("semanticSha256",input.semanticSha256) }))
        val chunkHashes = inputs.flatMap { it.descriptor.chunks }.map { it.ciphertextSha256 }.distinct().sorted()
        val readBytes = inputs.flatMap { it.descriptor.chunks }.distinctBy { it.ciphertextSha256 }.sumOf { it.ciphertextBytes.toLong() }
        require(readBytes <= 536_870_912) { "migration_budget_exceeded" }
        val snapshots=inputs.groupBy { it.snapshotId }.toSortedMap().map { (id,objects) ->
            require(objects.map { it.manifestSha256 }.distinct().size == 1)
            JSONObject().put("snapshotId",id).put("manifestSha256",objects.first().manifestSha256)
                .put("chunks",JSONArray(objects.flatMap { it.descriptor.chunks }.map { it.ciphertextSha256 }.distinct().sorted()))
        }
        val targetFormats = inputs.map { it.type }.distinct().sorted().map { JSONObject().put("type",it).put("version",1) }
        val plan = JSONObject().put("v",1).put("planId",planId).put("userId",userId).put("installationId",installationId)
            .put("libraryEpoch",epoch).put("contentGeneration",generation).put("transform",JSONObject(transform.toString()))
            .put("inputScope",JSONObject().put("snapshots",JSONArray(snapshots)).put("types",JSONArray(inputs.map { it.type }.distinct().sorted())))
            .put("inputMerkleRoot",merkle(chunkHashes)).put("localDetailsCommitment",detailsCommitment(details))
            .put("targetFormats",JSONArray(targetFormats)).put("validation",JSONObject().put("mode",mode).put("objectCount",inputs.size))
            .put("budget",JSONObject().put("cloudReadBytes",536_870_912).put("cloudWriteBytes",134_217_728).put("wallMillis",3_600_000).put("clientBytesPerRun",8_388_608).put("clientActiveMillisPerRun",30_000))
            .put("createdAt",now).put("expiresAt",expiresAt)
        return CloudMigrationDraft(plan,inputs,details)
    }
    fun detailsCommitment(details:JSONObject):String {
        CloudMigrationCrypto.exact(details,"objects")
        val objects=details.getJSONArray("objects")
        val hashes=(0 until objects.length()).map { index ->
            val item=objects.getJSONObject(index);CloudMigrationCrypto.exact(item,"inputDescriptor","semanticSha256")
            CloudMigrationCrypto.hash(item.getString("semanticSha256"))
            CloudSnapshotCodec.sha256(CloudMigrationJcs.bytes(item.getJSONObject("inputDescriptor")))
        }
        require(hashes==hashes.distinct().sorted()) { "migration_local_details_order" }
        return CloudSnapshotCodec.sha256("watchrss/r48/local-details/v1\n".toByteArray()+CloudMigrationJcs.bytes(details))
    }
    fun detailsFromGrant(inputObjects:JSONArray,invariants:JSONArray):JSONObject {
        require(inputObjects.length()==invariants.length())
        val semantics=(0 until invariants.length()).associate { i ->
            val item=invariants.getJSONObject(i);CloudMigrationCrypto.exact(item,"inputDescriptorSha256","semanticSha256")
            CloudMigrationCrypto.hash(item.getString("inputDescriptorSha256")) to CloudMigrationCrypto.hash(item.getString("semanticSha256"))
        }
        require(semantics.size==invariants.length())
        val rows=(0 until inputObjects.length()).map { i ->
            val descriptor=inputObjects.getJSONObject(i).getJSONObject("descriptor")
            val hash=CloudSnapshotCodec.sha256(CloudMigrationJcs.bytes(descriptor))
            hash to JSONObject().put("inputDescriptor",descriptor).put("semanticSha256",requireNotNull(semantics[hash]))
        }
        require(rows.map{it.first}.toSet()==semantics.keys && rows.map{it.first}.distinct().size==rows.size)
        return JSONObject().put("objects",JSONArray(rows.sortedBy{it.first}.map{it.second}))
    }
    fun merkle(hashes: List<String>): String {
        var level=hashes.distinct().sorted().map { CloudMigrationCrypto.hex(CloudSnapshotCodec.sha256(byteArrayOf(0)+CloudMigrationCrypto.hex(CloudMigrationCrypto.hash(it)))) }
        if(level.isEmpty()) return CloudSnapshotCodec.sha256(ByteArray(0))
        while(level.size>1) level=level.chunked(2).map { pair -> if(pair.size==1) pair[0] else CloudMigrationCrypto.hex(CloudSnapshotCodec.sha256(byteArrayOf(1)+pair[0]+pair[1])) }
        return level.single().joinToString("") { "%02x".format(it) }
    }
}
