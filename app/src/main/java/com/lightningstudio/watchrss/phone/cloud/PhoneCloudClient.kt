package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssItem
import com.lightningstudio.watchrss.phone.data.importer.ImportedRssSource
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhoneCloudClient(
    private val environment: AccountEnvironment,
    private val deviceAccessToken: () -> String? = { null },
    private val http: OkHttpClient = defaultHttpClient()
) {
    suspend fun membership(session: PhoneAccountSession): CloudMemberState =
        get(session, "/functions/v1/account/entitlement").use { response ->
            response.jsonBody().memberState()
        }

    suspend fun bootstrap(session: PhoneAccountSession): CloudBootstrap =
        post(session, "/functions/v1/cloud/bootstrap", JSONObject()).use { response ->
            val json = response.jsonBody()
            CloudBootstrap(
                member = json.getJSONObject("member").memberState(),
                devices = json.getJSONArray("devices").objects { it.device() },
                keyEnvelopes = json.getJSONArray("keyEnvelopes").objects { it.keyEnvelope() }
            )
        }

    suspend fun registerDevice(
        session: PhoneAccountSession,
        deviceId: String,
        displayName: String,
        publicKeySpki: String,
        keyVersion: Int
    ) {
        post(
            session,
            "/functions/v1/cloud/devices/register",
            JSONObject().apply {
                put("deviceId", deviceId)
                put("platform", "phone")
                put("displayName", displayName)
                put("publicKeySpki", publicKeySpki)
                put("keyVersion", keyVersion)
                put("capabilities", JSONObject().apply {
                    put("snapshotSchema", 2)
                    put("cloudRelay", true)
                    put("settings", false)
                    put("credentialEnvelopes", false)
                })
            }
        ).close()
    }

    suspend fun storeRecoveryEnvelope(
        session: PhoneAccountSession,
        createdByDeviceId: String,
        envelope: CloudKeyEnvelope
    ) {
        storeEnvelope(session, "recovery", null, createdByDeviceId, envelope)
    }

    suspend fun storeDeviceEnvelope(
        session: PhoneAccountSession,
        recipientDeviceId: String,
        createdByDeviceId: String,
        envelope: CloudKeyEnvelope
    ) {
        storeEnvelope(
            session,
            "device",
            recipientDeviceId,
            createdByDeviceId,
            envelope
        )
    }

    suspend fun reserveSnapshot(
        session: PhoneAccountSession,
        snapshot: EncryptedCloudSnapshot,
        retentionDays: Int?
    ): CloudSnapshotReservation {
        val manifestHash = CloudSnapshotCodec.sha256(snapshot.encryptedManifest)
        val chunks = JSONArray().apply {
            snapshot.manifest.allChunks.distinctBy(CloudChunkDescriptor::ciphertextSha256)
                .forEach { chunk ->
                    put(JSONObject().apply {
                        put("sha256", chunk.ciphertextSha256)
                        put("sizeBytes", chunk.ciphertextBytes)
                    })
                }
        }
        return post(
            session,
            "/functions/v1/cloud/snapshots/reserve",
            JSONObject().apply {
                put("snapshotId", snapshot.manifest.snapshotId)
                put("sourceDeviceId", snapshot.manifest.sourceDeviceId)
                put("deviceSequence", snapshot.manifest.deviceSequence)
                put("keyVersion", snapshot.manifest.keyVersion)
                put("manifest", JSONObject().apply {
                    put("sha256", manifestHash)
                    put("sizeBytes", snapshot.encryptedManifest.size)
                })
                put("parentHeads", JSONObject(snapshot.manifest.parentHeads))
                put("observedHeads", JSONObject(snapshot.manifest.observedHeads))
                put("retentionDays", retentionDays ?: JSONObject.NULL)
                put("chunks", chunks)
            }
        ).use { response ->
            val json = response.jsonBody()
            CloudSnapshotReservation(
                snapshotId = json.getString("snapshotId"),
                reservedBytes = json.getLong("reservedBytes"),
                missingObjects = json.getJSONArray("missingObjects").objects { item ->
                    CloudUploadObject(
                        kind = item.getString("kind"),
                        sha256 = item.getString("sha256"),
                        sizeBytes = item.getLong("sizeBytes"),
                        objectPath = item.getString("objectPath"),
                        signedUrl = item.getString("signedUrl"),
                        token = item.getString("token"),
                        tusEndpoint = item.getString("tusEndpoint"),
                        bucketName = item.getString("bucketName")
                    )
                }
            )
        }
    }

    suspend fun completeSnapshot(
        session: PhoneAccountSession,
        snapshot: EncryptedCloudSnapshot
    ) {
        post(
            session,
            "/functions/v1/cloud/snapshots/complete",
            JSONObject().apply {
                put("snapshotId", snapshot.manifest.snapshotId)
                put(
                    "chunkHashes",
                    JSONArray(
                        snapshot.manifest.allChunks
                            .map(CloudChunkDescriptor::ciphertextSha256)
                            .distinct()
                    )
                )
            }
        ).close()
    }

    suspend fun snapshotHeads(session: PhoneAccountSession): List<CloudSnapshotHead> =
        get(session, "/functions/v1/cloud/snapshots/heads").use { response ->
            response.jsonBody().getJSONArray("heads").objects { it.snapshotHead() }
        }

    suspend fun snapshot(
        session: PhoneAccountSession,
        snapshotId: String
    ): CloudSnapshotDownload =
        get(session, "/functions/v1/cloud/snapshots/$snapshotId").use { response ->
            val json = response.jsonBody()
            val snapshot = json.getJSONObject("snapshot")
            CloudSnapshotDownload(
                head = snapshot.snapshotHead(),
                manifestSignedUrl = snapshot.getString("manifestSignedUrl"),
                chunks = json.getJSONArray("chunks").objects { chunk ->
                    CloudDownloadObject(
                        sha256 = chunk.getString("sha256"),
                        sizeBytes = chunk.getLong("sizeBytes"),
                        signedUrl = chunk.getString("signedUrl")
                    )
                }
            )
        }

    suspend fun acknowledge(
        session: PhoneAccountSession,
        snapshotId: String,
        deviceId: String,
        result: String = "applied"
    ) {
        post(
            session,
            "/functions/v1/cloud/snapshots/$snapshotId/ack",
            JSONObject().apply {
                put("deviceId", deviceId)
                put("result", result)
            }
        ).close()
    }

    suspend fun revokeDevice(session: PhoneAccountSession, deviceId: String) {
        post(
            session,
            "/functions/v1/cloud/devices/$deviceId/revoke",
            JSONObject()
        ).close()
    }

    suspend fun rssInventory(
        session: PhoneAccountSession,
        sourceUrl: String,
        mode: CloudRssInventoryMode
    ): ImportedRssSource {
        val sourceId = post(
            session,
            "/functions/v1/rss/sources/resolve",
            JSONObject().apply { put("url", sourceUrl) }
        ).use { response ->
            response.jsonBody().getJSONObject("source").getString("id")
        }
        val limit = if (mode == CloudRssInventoryMode.ALL) "all" else "128"
        return get(
            session,
            "/functions/v1/rss/sources/$sourceId/entries?limit=$limit"
        ).use { response ->
            val json = response.jsonBody()
            val source = json.getJSONObject("source")
            ImportedRssSource(
                url = sourceUrl,
                title = source.optString("title"),
                description = source.optString("description"),
                siteUrl = source.nullableString("siteUrl"),
                imageUrl = source.nullableString("imageUrl"),
                items = json.getJSONArray("entries").objects { entry ->
                    val entryId = entry.getString("id")
                    ImportedRssItem(
                        url = entry.nullableString("link")
                            ?: "$sourceUrl${if ('?' in sourceUrl) '&' else '?'}watchrss_entry=$entryId",
                        title = entry.optString("title"),
                        excerpt = entry.optString("excerpt"),
                        contentHtml = entry.nullableString("contentHtml"),
                        contentText = entry.nullableString("contentText").orEmpty(),
                        imageUrl = entry.nullableString("imageUrl"),
                        guid = entry.nullableString("guid") ?: entryId
                    )
                }
            )
        }
    }

    suspend fun deleteSnapshot(session: PhoneAccountSession, snapshotId: String) {
        request(
            session,
            Request.Builder()
                .url(environment.backendBaseUrl + "/functions/v1/cloud/snapshots/$snapshotId")
                .delete()
        ).close()
    }

    suspend fun resetLibrary(session: PhoneAccountSession): CloudLibraryResetResult =
        post(
            session,
            "/functions/v1/cloud/library/reset",
            JSONObject().apply { put("confirmation", RESET_LIBRARY_CONFIRMATION) }
        ).use { response ->
            response.jsonBody().let { body ->
                require(body.optBoolean("libraryDeleted")) { "服务端未确认云端资料库删除" }
                CloudLibraryResetResult(
                    snapshotsDeleted = body.optLong("snapshotsDeleted"),
                    chunksDeleted = body.optLong("chunksDeleted"),
                    releasedBytes = body.optLong("releasedBytes"),
                    storageObjectsQueued = body.optLong("storageObjectsQueued")
                )
            }
        }

    suspend fun download(url: String, expectedSize: Long, expectedSha256: String): ByteArray =
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) response.throwHttp()
                val bytes = response.body?.bytes() ?: ByteArray(0)
                require(bytes.size.toLong() == expectedSize) { "云对象长度不匹配" }
                require(CloudSnapshotCodec.sha256(bytes) == expectedSha256) { "云对象哈希不匹配" }
                bytes
            }
        }

    private suspend fun storeEnvelope(
        session: PhoneAccountSession,
        recipientType: String,
        recipientDeviceId: String?,
        createdByDeviceId: String,
        envelope: CloudKeyEnvelope
    ) {
        post(
            session,
            "/functions/v1/cloud/key-envelopes",
            JSONObject().apply {
                put("recipientType", recipientType)
                put("recipientDeviceId", recipientDeviceId ?: JSONObject.NULL)
                put("keyVersion", envelope.keyVersion)
                put("algorithm", envelope.algorithm)
                put("wrappedKeyBase64", envelope.wrappedKeyBase64)
                put("nonceBase64", envelope.nonceBase64)
                put("ephemeralPublicKeySpki", envelope.ephemeralPublicKeySpki ?: JSONObject.NULL)
                put("createdByDeviceId", createdByDeviceId)
            }
        ).close()
    }

    private suspend fun post(
        session: PhoneAccountSession,
        path: String,
        body: JSONObject
    ): okhttp3.Response = request(
        session,
        Request.Builder()
            .url(environment.backendBaseUrl + path)
            .post(body.toString().toRequestBody(JSON))
    )

    private suspend fun get(
        session: PhoneAccountSession,
        path: String
    ): okhttp3.Response = request(
        session,
        Request.Builder().url(environment.backendBaseUrl + path).get()
    )

    private suspend fun request(
        session: PhoneAccountSession,
        builder: Request.Builder
    ): okhttp3.Response = withContext(Dispatchers.IO) {
        require(environment.backendBaseUrl.isNotBlank()) { "云服务地址未配置" }
        require(!session.isExpired) { "登录已过期，请重新登录" }
        val request = builder
            .withWatchRssAppVersionHeader()
            .header("authorization", "Bearer ${session.accessToken}")
            .apply {
                deviceAccessToken()?.takeIf { it.isNotBlank() }?.let {
                    header("x-watchrss-device-authorization", "Bearer $it")
                }
            }
            .header("apikey", environment.supabaseAnonKey)
            .header("accept", "application/json")
            .build()
        http.newCall(request).execute().also { response ->
            if (!response.isSuccessful) response.throwHttp()
        }
    }

    private fun okhttp3.Response.throwHttp(): Nothing {
        val detail = body?.string().orEmpty()
        close()
        throw IOException("HTTP $code: ${detail.ifBlank { message }}")
    }

    private fun okhttp3.Response.jsonBody(): JSONObject {
        val text = body?.string().orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun JSONObject.memberState() = CloudMemberState(
        plan = optString("plan", "free"),
        active = optBoolean("active"),
        writable = optBoolean("writable"),
        readable = optBoolean("readable"),
        quotaBytes = optLong("quotaBytes"),
        usedBytes = optLong("usedBytes"),
        reservedBytes = optLong("reservedBytes"),
        retentionDays = if (isNull("retentionDays")) null else optInt("retentionDays"),
        readOnlyAt = nullableString("readOnlyAt"),
        deleteAfter = nullableString("deleteAfter")
    )

    private fun JSONObject.device() = RegisteredCloudDevice(
        deviceId = getString("deviceId"),
        platform = getString("platform"),
        displayName = optString("displayName"),
        publicKeySpki = optString("publicKeySpki"),
        keyVersion = optInt("keyVersion", 1),
        lastSequence = optLong("lastSequence"),
        revokedAt = nullableString("revokedAt")
    )

    private fun JSONObject.keyEnvelope() = StoredCloudKeyEnvelope(
        id = getString("id"),
        recipientType = getString("recipientType"),
        recipientDeviceId = nullableString("recipientDeviceId"),
        envelope = CloudKeyEnvelope(
            algorithm = getString("algorithm"),
            keyVersion = getInt("keyVersion"),
            wrappedKeyBase64 = getString("wrappedKeyBase64"),
            nonceBase64 = getString("nonceBase64"),
            ephemeralPublicKeySpki = nullableString("ephemeralPublicKeySpki")
        )
    )

    private fun JSONObject.snapshotHead() = CloudSnapshotHead(
        id = getString("id"),
        sourceDeviceId = getString("sourceDeviceId"),
        deviceSequence = getLong("deviceSequence"),
        keyVersion = optInt("keyVersion", 1),
        manifestSha256 = getString("manifestSha256"),
        manifestSizeBytes = getLong("manifestSizeBytes"),
        parentHeads = getJSONObject("parentHeads").stringMap(),
        observedHeads = getJSONObject("observedHeads").longMap()
    )

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.stringMap(): Map<String, String> =
        keys().asSequence().associateWith(::getString)

    private fun JSONObject.longMap(): Map<String, Long> =
        keys().asSequence().associateWith(::getLong)

    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
        buildList {
            for (index in 0 until length()) add(transform(getJSONObject(index)))
        }

    companion object {
        private const val RESET_LIBRARY_CONFIRMATION = "DELETE_CLOUD_LIBRARY"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
