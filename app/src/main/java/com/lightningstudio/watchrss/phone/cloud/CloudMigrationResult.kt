package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.sqrt

class CloudMigrationOutput(val input: CloudMigrationInput, val descriptor: CloudObjectDescriptor) {
    override fun toString() = "CloudMigrationOutput(redacted)"
}

object CloudMigrationResult {
    fun sampleCount(n: Int): Int { require(n>=0); return minOf(n,maxOf(8,ceil(sqrt(n.toDouble())).toInt())) }
    fun permutation(n: Int, random: SecureRandom = SecureRandom()): List<Int> {
        val indices=(0 until n).toMutableList()
        for(i in indices.lastIndex downTo 1) { val j=random.nextInt(i+1);val old=indices[i];indices[i]=indices[j];indices[j]=old }
        return indices
    }
    fun validate(receipt: JSONObject, draft: CloudMigrationDraft, jobId: String): List<CloudMigrationOutput> {
        CloudMigrationCrypto.exact(receipt,"format","version","planId","planHash","jobId","transform","objects","failed")
        require(receipt.getString("format")=="watchrss-r48-receipt" && receipt.getInt("version")==1)
        require(receipt.getString("planId")==draft.plan.getString("planId") && receipt.getString("planHash")==draft.planHash && receipt.getString("jobId")==jobId)
        require(CloudMigrationJcs.canonical(receipt.getJSONObject("transform").toString())==CloudMigrationJcs.canonical(draft.plan.getJSONObject("transform").toString()))
        val inputs=draft.inputs.associateBy { it.descriptorSha256 }
        require(inputs.size==draft.inputs.size)
        val processed=mutableSetOf<String>()
        val objects=receipt.getJSONArray("objects")
        val outputs=(0 until objects.length()).map { i ->
            val objectValue=objects.getJSONObject(i)
            CloudMigrationCrypto.exact(objectValue,"inputDescriptorSha256","semanticSha256","outputSchema","outputDescriptor")
            val id=objectValue.getString("inputDescriptorSha256");require(processed.add(id))
            val input=requireNotNull(inputs[id])
            require(objectValue.getString("semanticSha256")==input.semanticSha256 && objectValue.getString("outputSchema")=="r48.body-output.v1")
            CloudMigrationOutput(input,strictDescriptor(objectValue.getJSONObject("outputDescriptor"),input.descriptor.name))
        }
        val failures=receipt.getJSONArray("failed")
        for(i in 0 until failures.length()) {
            val failure=failures.getJSONObject(i);CloudMigrationCrypto.exact(failure,"inputDescriptorSha256","code")
            val id=failure.getString("inputDescriptorSha256");require(id in inputs && processed.add(id))
            require(failure.getString("code") in setOf("unsupported_format","input_missing","input_invalid","budget_exceeded","transform_failed"))
        }
        require(processed==inputs.keys && outputs.isNotEmpty()) { "migration_result_mismatch" }
        require(outputs.sumOf { it.descriptor.chunks.sumOf { c -> c.ciphertextBytes.toLong() } }<=draft.plan.getJSONObject("budget").getLong("cloudWriteBytes"))
        return outputs
    }
    fun strictDescriptor(value:JSONObject, expectedName:String):CloudObjectDescriptor {
        CloudMigrationCrypto.exact(value,"name","encoding","originalBytes","encodedBytes","chunks")
        require(value.getString("name")==expectedName && expectedName.matches(Regex("[a-z0-9][a-z0-9._/-]{0,127}")))
        require(value.getString("encoding") in setOf("identity","gzip","gzip-chunks-v1"))
        val original=CloudMigrationCrypto.integer(value,"originalBytes"); val encoded=CloudMigrationCrypto.integer(value,"encodedBytes")
        require(original<=536_870_912 && encoded<=134_217_728)
        val chunks=value.getJSONArray("chunks");require(chunks.length() in 1..4096)
        var bytes=0L
        for(i in 0 until chunks.length()) {
            val c=chunks.getJSONObject(i);CloudMigrationCrypto.exact(c,*CloudChunkDescriptor.fields.toTypedArray())
            CloudMigrationCrypto.hash(c.getString("ciphertextSha256"));CloudMigrationCrypto.hash(c.getString("plaintextSha256"))
            CloudMigrationCrypto.b64(c.getString("saltBase64"),16);CloudMigrationCrypto.b64(c.getString("nonceBase64"),12)
            val plain=CloudMigrationCrypto.integer(c,"plaintextBytes");val cipher=CloudMigrationCrypto.integer(c,"ciphertextBytes")
            require(plain in 1..4_194_304 && cipher==plain+51);bytes+=plain
        }
        require(bytes==encoded && (value.getString("encoding")!="identity" || original==encoded))
        return CloudObjectDescriptor.fromRaw(value.toString())
    }

    /** Only descriptors identical to the authenticated frozen input can be replaced. */
    fun rebase(latest:CloudSnapshotManifest, outputs:List<CloudMigrationOutput>, accountKey:ByteArray, root:ByteArray, planId:String):List<CloudObjectDescriptor> {
        latest.requireWritable()
        val byName=outputs.associateBy { it.input.descriptor.name }
        return latest.objects.map { original ->
            val output=byName[original.name]
            if(output==null || original.toRawString()!=output.input.descriptor.toRawString()) original
            else {
                var raw=original.rawDocument ?: CloudRawDocument.parse(original.toRawString())
                for(field in listOf("entryRef","readerKeyEnvelope","objectKeyEnvelope","chunks"))raw=raw.remove(listOf(field))
                val projected=output.descriptor.copy(privateArticle=original.privateArticle,privateBook=original.privateBook,rawDocument=raw)
                CloudObjectWriter.migrationOutput(projected,accountKey,root,planId)
            }
        }
    }
}
