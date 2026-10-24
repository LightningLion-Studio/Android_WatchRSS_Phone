package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

const val CLOUD_SNAPSHOT_SCHEMA_VERSION = 2
const val CLOUD_SNAPSHOT_CHUNK_BYTES = 4 * 1024 * 1024

data class CloudLogicalObject(
    val name: String,
    val bytes: ByteArray,
    val compress: Boolean = true,
    val file: File? = null,
    val incrementalChunks: Boolean = false
) {
    constructor(name: String, file: File, compress: Boolean = true) :
        this(name, ByteArray(0), compress, file)

    val originalBytes: Long
        get() = file?.length() ?: bytes.size.toLong()

    fun openStream(): InputStream = file?.inputStream()?.buffered() ?: bytes.inputStream()

    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class CloudChunkDescriptor(
    val plaintextSha256: String,
    val ciphertextSha256: String,
    val plaintextBytes: Int,
    val ciphertextBytes: Int,
    val saltBase64: String,
    val nonceBase64: String,
    val rawDocument: CloudRawDocument? = null
) {
    private fun knownJson(): JSONObject = JSONObject().apply {
        put("plaintextSha256", plaintextSha256)
        put("ciphertextSha256", ciphertextSha256)
        put("plaintextBytes", plaintextBytes)
        put("ciphertextBytes", ciphertextBytes)
        put("saltBase64", saltBase64)
        put("nonceBase64", nonceBase64)
    }

    fun toRawString():String=(rawDocument?:CloudRawDocument.parse(knownJson().toString())).merge(emptyList(),knownJson()).source
    fun toJson():JSONObject=CloudRawDocument.parse(toRawString()).json()
    companion object {
        val fields=setOf("plaintextSha256","ciphertextSha256","plaintextBytes","ciphertextBytes","saltBase64","nonceBase64")
        fun fromJson(json: JSONObject): CloudChunkDescriptor = fromRaw(json.toString())
        fun fromRaw(raw:String):CloudChunkDescriptor {
            val doc=CloudRawDocument.parse(raw);val json=doc.json()
            return CloudChunkDescriptor(
                plaintextSha256 = json.getString("plaintextSha256"),
                ciphertextSha256 = json.getString("ciphertextSha256"),
                plaintextBytes = json.getInt("plaintextBytes"),
                ciphertextBytes = json.getInt("ciphertextBytes"),
                saltBase64 = json.getString("saltBase64"),
                nonceBase64 = json.getString("nonceBase64"),
                rawDocument = doc.takeIf{it.unknownBytes(emptyList(),fields)>0}
            )
        }
    }
}

data class CloudObjectDescriptor(
    val name: String,
    val encoding: String,
    val originalBytes: Long,
    val encodedBytes: Long,
    val chunks: List<CloudChunkDescriptor>,
    val readerKeyEnvelope: JSONObject? = null,
    val privateArticle: JSONObject? = null,
    val privateBook: JSONObject? = null,
    val entryRef: JSONObject? = null,
    val objectKeyEnvelope: JSONObject? = null,
    val rawDocument: CloudRawDocument? = null
) {
    private fun knownJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("encoding", encoding)
        put("originalBytes", originalBytes)
        put("encodedBytes", encodedBytes)
        readerKeyEnvelope?.let { put("readerKeyEnvelope", it) }
        privateArticle?.let { put("privateArticle", it) }
        privateBook?.let { put("privateBook",it) }
        entryRef?.let {put("entryRef",it)}
        objectKeyEnvelope?.let {put("objectKeyEnvelope",it)}
        put("chunks", JSONArray().apply { chunks.forEach { put(it.toJson()) } })
    }

    val access:CloudFormatAccess get() {
        if(encoding !in setOf("identity","gzip","gzip-chunks-v1"))return CloudFormatAccess.OPAQUE
        val doc=rawDocument?:CloudRawDocument.parse(knownJson().toString())
        return CloudFormatR48.access(doc,known=CloudFormatR48.descriptorFeatures,
            unknownBytes=unknownBytes,limit=4096)
    }
    val unknownBytes:Int get() {
        val doc=rawDocument
        return (doc?.unknownBytes(emptyList(),fields)?:0)+chunks.sumOf{it.rawDocument?.unknownBytes(emptyList(),CloudChunkDescriptor.fields)?:0}+
            (doc?.unknownBytes(listOf("privateBook"),setOf("bookId","fingerprint","title","chapterCount","sourceUrl","kind","packagePath"))?:0)+
            (doc?.unknownBytes(listOf("privateArticle"),PhoneCloudRecordCodec.knownFields("article"))?:0)
    }
    val accountRoot:Boolean get()=readerKeyEnvelope==null&&objectKeyEnvelope==null&&entryRef==null
    fun toRawString():String {
        if(access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED))return rawDocument?.source ?: knownJson().toString()
        var doc=(rawDocument?:CloudRawDocument.parse(knownJson().toString())).merge(emptyList(),knownJson())
        if(entryRef==null)doc=doc.patch(listOf("chunks"),chunks.joinToString(",","[","]"){it.toRawString()})
        else doc=doc.remove(listOf("chunks"))
        return doc.source
    }
    fun toJson():JSONObject=CloudRawDocument.parse(toRawString()).json()
    companion object {
        val fields=setOf("name","encoding","originalBytes","encodedBytes","chunks","readerKeyEnvelope","privateArticle","privateBook","entryRef","objectKeyEnvelope","features")
        fun fromJson(json: JSONObject): CloudObjectDescriptor = fromRaw(json.toString())
        fun fromRaw(raw:String):CloudObjectDescriptor {
            val doc=CloudRawDocument.parse(raw);val json=doc.json()
            val nodes=doc.node(listOf("chunks"))?.elements.orEmpty()
            val opaque=json.optString("encoding") !in setOf("identity","gzip","gzip-chunks-v1") || CloudFormatR48.access(doc,known=CloudFormatR48.descriptorFeatures,unknownBytes=doc.unknownBytes(emptyList(),fields),limit=4096) in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED)
            if(!opaque) {
                require(!(json.has("readerKeyEnvelope")&&json.has("objectKeyEnvelope")))
                for(key in listOf("readerKeyEnvelope","objectKeyEnvelope","entryRef","privateArticle","privateBook"))if(json.has(key))require(json.optJSONObject(key)!=null)
                if(json.has("entryRef"))require(!json.has("chunks")&&!json.has("readerKeyEnvelope")&&!json.has("objectKeyEnvelope"))
            }
            return CloudObjectDescriptor(
                name=json.getString("name"),encoding=if(opaque)json.optString("encoding","unknown")else json.getString("encoding"),
                originalBytes=if(opaque)json.optLong("originalBytes")else json.getLong("originalBytes"),encodedBytes=if(opaque)json.optLong("encodedBytes")else json.getLong("encodedBytes"),
                chunks=if(opaque)nodes.mapNotNull{runCatching{CloudChunkDescriptor.fromRaw(raw.substring(it.start,it.end))}.getOrNull()}else nodes.map{CloudChunkDescriptor.fromRaw(raw.substring(it.start,it.end))},
                readerKeyEnvelope=json.optJSONObject("readerKeyEnvelope"),privateArticle=json.optJSONObject("privateArticle"),privateBook=json.optJSONObject("privateBook"),
                entryRef=json.optJSONObject("entryRef"),objectKeyEnvelope=json.optJSONObject("objectKeyEnvelope"),
                rawDocument=doc.takeIf{opaque||json.has("features")||json.has("entryRef")||json.has("objectKeyEnvelope")||it.unknownBytes(emptyList(),fields)>0||it.unknownBytes(listOf("privateArticle"),PhoneCloudRecordCodec.knownFields("article"))>0||it.unknownBytes(listOf("privateBook"),setOf("bookId","fingerprint","title","chapterCount","sourceUrl","kind","packagePath"))>0}
            )
        }
    }
}

data class CloudSnapshotManifest(
    val snapshotId: String,
    val sourceDeviceId: String,
    val deviceSequence: Long,
    val keyVersion: Int,
    val createdAtMillis: Long,
    val parentHeads: Map<String, String>,
    val observedHeads: Map<String, Long>,
    val objects: List<CloudObjectDescriptor>,
    val settings: JSONObject = JSONObject(),
    val credentialEnvelopes: JSONObject = JSONObject(),
    val rawDocument: CloudRawDocument? = null
) {
    val allChunks: List<CloudChunkDescriptor>
        get() = objects.flatMap(CloudObjectDescriptor::chunks)

    private fun knownJson(): JSONObject = JSONObject().apply {
        put("format", "watchrss-cloud-snapshot")
        put("schemaVersion", CLOUD_SNAPSHOT_SCHEMA_VERSION)
        put("snapshotId", snapshotId)
        put("sourceDeviceId", sourceDeviceId)
        put("deviceSequence", deviceSequence)
        put("keyVersion", keyVersion)
        put("createdAtMillis", createdAtMillis)
        put("parentHeads", JSONObject(parentHeads))
        put("observedHeads", JSONObject(observedHeads))
        put("objects", JSONArray().apply { objects.forEach { put(it.toJson()) } })
        put("settings", settings)
        put("credentialEnvelopes", credentialEnvelopes)
    }

    val access:CloudFormatAccess get() {
        val doc=rawDocument?:CloudRawDocument.parse(knownJson().toString())
        val unknown=doc.unknownBytes(emptyList(),fields)+objects.sumOf{it.unknownBytes}+
            doc.unknownBytes(listOf("settings"),setOf("snapshotContentLayout","libraryEpoch","ownerUserId","backendScope","contentGeneration","snapshotContentHashV3","privateBodyFingerprintV2","privateBodyArticleIds","privateBodyBookIds"))
        return CloudFormatR48.access(doc,known=emptySet(),unknownBytes=unknown,limit=1048576)
    }
    fun objectSubset(selected:List<CloudObjectDescriptor>):CloudSnapshotManifest {
        val names=selected.map{it.name}.toMutableSet()
        selected.mapNotNull{it.entryRef?.getString("object")}.forEach(names::add)
        return copy(objects=objects.filter{it.name in names})
    }
    fun chunksFor(descriptor:CloudObjectDescriptor):List<CloudChunkDescriptor> = objectSubset(listOf(descriptor)).allChunks
    fun requireReadable(){if(access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED))throw CloudFormatUpgradeRequired("manifest_opaque")}
    fun requireWritable(){if(access!=CloudFormatAccess.WRITABLE)throw CloudFormatUpgradeRequired("manifest_read_only")}
    fun toRawString():String {
        if(access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED))return rawDocument?.source ?: knownJson().toString()
        return (rawDocument?:CloudRawDocument.parse(knownJson().toString())).merge(emptyList(),knownJson())
            .patch(listOf("objects"),objects.joinToString(",","[","]"){it.toRawString()}).source
    }
    fun toJson():JSONObject=CloudRawDocument.parse(toRawString()).json()
    companion object {
        val fields=setOf("format","schemaVersion","snapshotId","sourceDeviceId","deviceSequence","keyVersion","createdAtMillis","parentHeads","observedHeads","objects","settings","credentialEnvelopes","features")
        fun fromJson(json: JSONObject): CloudSnapshotManifest = fromRaw(json.toString())
        fun fromRaw(raw:String):CloudSnapshotManifest {
            val doc=CloudRawDocument.parse(raw);val json=doc.json()
            require(json.getString("format") == "watchrss-cloud-snapshot") {
                "不是腕上RSS云快照"
            }
            require(json.getInt("schemaVersion") == CLOUD_SNAPSHOT_SCHEMA_VERSION) {
                "不支持的云快照版本"
            }
            val opaque=CloudFormatR48.access(doc,known=emptySet(),unknownBytes=doc.unknownBytes(emptyList(),fields),limit=1048576) in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED)
            return CloudSnapshotManifest(
                snapshotId = json.getString("snapshotId"),
                sourceDeviceId = json.getString("sourceDeviceId"),
                deviceSequence = json.getLong("deviceSequence"),
                keyVersion = json.optInt("keyVersion", 1),
                createdAtMillis = json.getLong("createdAtMillis"),
                parentHeads = json.getJSONObject("parentHeads").toStringMap(),
                observedHeads = json.getJSONObject("observedHeads").toLongMap(),
                objects = if(opaque)doc.node(listOf("objects"))?.elements.orEmpty().mapNotNull{runCatching{CloudObjectDescriptor.fromRaw(raw.substring(it.start,it.end))}.getOrNull()}else requireNotNull(doc.node(listOf("objects"))?.elements).map{CloudObjectDescriptor.fromRaw(raw.substring(it.start,it.end))},
                settings = json.optJSONObject("settings") ?: JSONObject(),
                credentialEnvelopes = json.optJSONObject("credentialEnvelopes") ?: JSONObject(),
                rawDocument=doc.takeIf{json.has("features")||it.unknownBytes(emptyList(),fields)>0||json.has("settings")||json.has("credentialEnvelopes")}
            )
        }
    }
}

data class EncryptedCloudSnapshot(
    val manifest: CloudSnapshotManifest,
    val encryptedManifest: ByteArray,
    val newCiphertextChunks: Map<String, ByteArray>
)

private fun <T> JSONArray.toObjectList(transform: (JSONObject) -> T): List<T> =
    buildList {
        for (index in 0 until length()) {
            add(transform(getJSONObject(index)))
        }
    }

private fun JSONObject.toStringMap(): Map<String, String> =
    keys().asSequence().associateWith { getString(it) }

private fun JSONObject.toLongMap(): Map<String, Long> =
    keys().asSequence().associateWith { getLong(it) }

internal fun validateCloudManifestHead(manifest: CloudSnapshotManifest, head: CloudSnapshotHead) {
    require(manifest.snapshotId == head.id && manifest.sourceDeviceId == head.sourceDeviceId &&
        manifest.deviceSequence == head.deviceSequence && manifest.keyVersion == head.keyVersion) { "云快照清单与头信息不一致" }
    if (head.sourceDeviceId.startsWith("reader-migration-")) require(manifest.objects.all { it.name.startsWith("reader/") }) { "阅读投影含有非阅读对象" }
}
