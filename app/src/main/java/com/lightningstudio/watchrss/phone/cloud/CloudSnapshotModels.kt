package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject

const val CLOUD_SNAPSHOT_SCHEMA_VERSION = 2
const val CLOUD_SNAPSHOT_CHUNK_BYTES = 4 * 1024 * 1024

data class CloudLogicalObject(
    val name: String,
    val bytes: ByteArray,
    val compress: Boolean = true
)

data class CloudChunkDescriptor(
    val plaintextSha256: String,
    val ciphertextSha256: String,
    val plaintextBytes: Int,
    val ciphertextBytes: Int,
    val saltBase64: String,
    val nonceBase64: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("plaintextSha256", plaintextSha256)
        put("ciphertextSha256", ciphertextSha256)
        put("plaintextBytes", plaintextBytes)
        put("ciphertextBytes", ciphertextBytes)
        put("saltBase64", saltBase64)
        put("nonceBase64", nonceBase64)
    }

    companion object {
        fun fromJson(json: JSONObject): CloudChunkDescriptor =
            CloudChunkDescriptor(
                plaintextSha256 = json.getString("plaintextSha256"),
                ciphertextSha256 = json.getString("ciphertextSha256"),
                plaintextBytes = json.getInt("plaintextBytes"),
                ciphertextBytes = json.getInt("ciphertextBytes"),
                saltBase64 = json.getString("saltBase64"),
                nonceBase64 = json.getString("nonceBase64")
            )
    }
}

data class CloudObjectDescriptor(
    val name: String,
    val encoding: String,
    val originalBytes: Long,
    val encodedBytes: Long,
    val chunks: List<CloudChunkDescriptor>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("encoding", encoding)
        put("originalBytes", originalBytes)
        put("encodedBytes", encodedBytes)
        put("chunks", JSONArray().apply { chunks.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): CloudObjectDescriptor =
            CloudObjectDescriptor(
                name = json.getString("name"),
                encoding = json.getString("encoding"),
                originalBytes = json.getLong("originalBytes"),
                encodedBytes = json.getLong("encodedBytes"),
                chunks = json.getJSONArray("chunks").toObjectList(CloudChunkDescriptor::fromJson)
            )
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
    val credentialEnvelopes: JSONObject = JSONObject()
) {
    val allChunks: List<CloudChunkDescriptor>
        get() = objects.flatMap(CloudObjectDescriptor::chunks)

    fun toJson(): JSONObject = JSONObject().apply {
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

    companion object {
        fun fromJson(json: JSONObject): CloudSnapshotManifest {
            require(json.getString("format") == "watchrss-cloud-snapshot") {
                "不是腕上RSS云快照"
            }
            require(json.getInt("schemaVersion") == CLOUD_SNAPSHOT_SCHEMA_VERSION) {
                "不支持的云快照版本"
            }
            return CloudSnapshotManifest(
                snapshotId = json.getString("snapshotId"),
                sourceDeviceId = json.getString("sourceDeviceId"),
                deviceSequence = json.getLong("deviceSequence"),
                keyVersion = json.optInt("keyVersion", 1),
                createdAtMillis = json.getLong("createdAtMillis"),
                parentHeads = json.getJSONObject("parentHeads").toStringMap(),
                observedHeads = json.getJSONObject("observedHeads").toLongMap(),
                objects = json.getJSONArray("objects").toObjectList(CloudObjectDescriptor::fromJson),
                settings = json.optJSONObject("settings") ?: JSONObject(),
                credentialEnvelopes = json.optJSONObject("credentialEnvelopes") ?: JSONObject()
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
