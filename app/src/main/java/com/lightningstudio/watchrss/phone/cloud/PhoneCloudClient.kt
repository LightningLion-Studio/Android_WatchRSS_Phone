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
    private val http: OkHttpClient = defaultHttpClient(),
    private val refreshDeviceSession: suspend (String, String) -> Boolean = { _, _ -> false },
    private val downloadBackoff: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) {
    val backendBaseUrl: String get() = environment.backendBaseUrl.trimEnd('/')
    private val epochs = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val contentGenerations = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var http1Uploads = false
    internal var onUploadTransportEvent: (String) -> Unit = {}
    internal var onContentMetadata: (PhoneAccountSession, CloudContentMetadata) -> Unit = { _, _ -> }
    internal fun useContentGeneration(session: PhoneAccountSession, generation: String) {
        require(java.util.UUID.fromString(generation).toString() == generation)
        contentGenerations[scopeId(session)] = generation
    }
    internal fun knownContentGeneration(session: PhoneAccountSession): String? = contentGenerations[scopeId(session)]
    private suspend fun observeContentMetadata(session: PhoneAccountSession, json: JSONObject, requestGeneration: CloudContentRequestGeneration?) {
        val metadata = CloudContentMetadata.fromJson(json) ?: return
        val current = knownContentGeneration(session)
        // An older foreground/bootstrap response must not roll a generation back after a newer response.
        if (requestGeneration != null && current != requestGeneration.value && current != metadata.generation) throw cloudContentRebuildRequired()
        onContentMetadata(session, metadata)
        useContentGeneration(session, metadata.generation)
        val fence = kotlinx.coroutines.currentCoroutineContext()[CloudContentGenerationFence]
        if (fence?.scope == scopeId(session) && fence.generation != metadata.generation) throw cloudContentRebuildRequired()
    }
    internal suspend fun <T> inContentGeneration(session: PhoneAccountSession, operation: suspend () -> T): T =
        kotlinx.coroutines.withContext(CloudContentGenerationFence(scopeId(session), knownContentGeneration(session))) { operation() }
    internal var checkSession: (PhoneAccountSession) -> Unit = {}
    internal var onDeviceKeyConflict: (PhoneAccountSession) -> Unit = {}
    internal var onEpochMismatch: (PhoneAccountSession) -> Unit = {}
    internal var onBootstrap: suspend (PhoneAccountSession, CloudBootstrap) -> Unit = { _, _ -> }
    internal var onRecordMetadata: suspend (PhoneAccountSession, JSONObject) -> Unit = { _, _ -> }
    fun scopeId(session: PhoneAccountSession): String = CloudSnapshotCodec.sha256("$backendBaseUrl\n${session.userId}".toByteArray())
    fun useEpoch(session: PhoneAccountSession, epoch: String) { require(epoch.isNotBlank()); epochs[scopeId(session)] = epoch }
    internal fun knownEpoch(session: PhoneAccountSession): String? = epochs[scopeId(session)]
    fun forgetEpoch(session: PhoneAccountSession) { epochs.remove(scopeId(session)) }
    fun epoch(session: PhoneAccountSession): String = epochs[scopeId(session)] ?: error("请先读取云资料库纪元")
    internal suspend fun v2Post(session: PhoneAccountSession, path: String, body: JSONObject, expectedEpoch: String? = knownEpoch(session)): JSONObject =
        post(session, path, body, expectedEpoch).use { it.jsonBody() }
    internal suspend fun v2Get(session: PhoneAccountSession, path: String): JSONObject = get(session, path).use { it.jsonBody() }
    suspend fun storeCommitments(session: PhoneAccountSession, deviceId: String, values: List<CloudKeyCommitment>, expectedEpoch: String? = knownEpoch(session)) {
        v2Post(session, "/functions/v1/cloud/keys/commitments", JSONObject().put("deviceId", deviceId)
            .put("commitments", JSONArray().apply { values.forEach { put(JSONObject().put("keyVersion", it.keyVersion)
                .put("commitment", it.commitment).put("rotationProof", it.rotationProof ?: JSONObject.NULL)) } }), expectedEpoch)
    }
    internal var currentDeviceId: String? = null
    suspend fun rotate(session: PhoneAccountSession, body: JSONObject, expectedEpoch: String? = knownEpoch(session)) { v2Post(session, "/functions/v1/cloud/keys/rotate", body, expectedEpoch) }

    internal suspend fun recordPage(session: PhoneAccountSession, cursor: String): JSONObject? {
        require(cursor.matches(Regex("[0-9]+")))
        return try { get(session, "/functions/v1/cloud/records?after=$cursor&limit=50" + (currentDeviceId?.let { "&deviceId=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")).use { response -> response.jsonBody().also { onRecordMetadata(session, it); observeContentMetadata(session, it, response.request.tag(CloudContentRequestGeneration::class.java)) } } }
        catch (error: CloudHttpException) { if (error.status == 404 && !discardedCloudTransfer(error)) null else throw error }
    }

    internal suspend fun postRecords(session: PhoneAccountSession, items: JSONArray, expectedEpoch: String? = knownEpoch(session)): JSONObject =
        post(session, "/functions/v1/cloud/records", JSONObject().put("formatVersion", 1).put("items", items), expectedEpoch).use { it.jsonBody() }

    suspend fun membership(session: PhoneAccountSession): CloudMemberState =
        get(session, "/functions/v1/account/entitlement").use { response ->
            response.jsonBody().memberState()
        }

    suspend fun bootstrap(session: PhoneAccountSession): CloudBootstrap {
        val (json, requestedGeneration) = post(session, "/functions/v1/cloud/bootstrap", JSONObject()).use { it.jsonBody() to it.request.tag(CloudContentRequestGeneration::class.java) }
        val result = CloudBootstrap(
            member = json.getJSONObject("member").memberState(),
            devices = json.getJSONArray("devices").objects { it.device() }.filterNot { it.deviceId.startsWith("reader-migration-") },
            keyEnvelopes = json.getJSONArray("keyEnvelopes").objects { it.keyEnvelope() },
            libraryEpoch = json.optString("libraryEpoch"), keyVersionFloor = json.optInt("keyVersionFloor"),
            keyCommitments = parseCommitments(json),
            legacyKeyVersions = json.optJSONArray("legacyKeyVersions")?.let { versions -> (0 until versions.length()).map { versions.getInt(it) } } ?: emptyList(),
            contentGeneration = json.nullableString("contentGeneration"), contentClearedAt = json.nullableString("contentClearedAt"),
            contentEmpty = json.optBoolean("contentEmpty"))
        onBootstrap(session, result)
        observeContentMetadata(session, json, requestedGeneration)
        return result
    }

    internal fun parseCommitments(json: JSONObject): List<CloudKeyCommitment> =
        (json.optJSONArray("keyCommitments") ?: JSONArray()).objects {
            CloudKeyCommitment(it.getInt("keyVersion"), it.getString("commitment"), it.nullableString("rotationProof"))
        }

    suspend fun registerDevice(
        session: PhoneAccountSession,
        deviceId: String,
        displayName: String,
        publicKeySpki: String,
        keyVersion: Int,
        attestation: String? = null,
        attestationKeyVersion: Int? = null,
        expectedEpoch: String? = knownEpoch(session)
    ) {
        post(
            session,
            "/functions/v1/cloud/devices/register",
            JSONObject().apply {
                put("deviceId", deviceId)
                put("platform", "phone")
                attestation?.let { put("attestation", it); put("attestationKeyVersion", attestationKeyVersion) }
                put("displayName", displayName)
                put("publicKeySpki", publicKeySpki)
                put("keyVersion", keyVersion)
                put("capabilities", JSONObject().apply {
                    put("snapshotSchema", 2)
                    put("rssRecordsVersion", 1)
                    put("cloudRelay", true)
                    put("settings", false)
                    put("credentialEnvelopes", false)
                })
            }, expectedEpoch
        ).close()
    }

    /** R28: supplement a server-confirmed watch; omit displayName/capabilities, never create its identity. */
    internal suspend fun attestPairingWatch(session: PhoneAccountSession, watch: RegisteredCloudDevice,
        attestation: String, attestationKeyVersion: Int, expectedEpoch: String) {
        require(watch.revokedAt == null && !watch.watchSelfRegisteredAt.isNullOrBlank()) { "手表尚未自行登记" }
        post(session, "/functions/v1/cloud/devices/register", JSONObject().put("deviceId", watch.deviceId)
            .put("platform", watch.platform).put("publicKeySpki", watch.publicKeySpki.base64Bytes().base64())
            .put("keyVersion", watch.keyVersion).put("attestation", attestation).put("attestationKeyVersion", attestationKeyVersion), expectedEpoch).close()
    }

    suspend fun storeRecoveryEnvelope(
        session: PhoneAccountSession,
        createdByDeviceId: String,
        envelope: CloudKeyEnvelope,
        expectedEpoch: String? = knownEpoch(session)
    ) {
        storeEnvelope(session, "recovery", null, createdByDeviceId, envelope, expectedEpoch)
    }

    suspend fun storeDeviceEnvelope(
        session: PhoneAccountSession,
        recipientDeviceId: String,
        createdByDeviceId: String,
        envelope: CloudKeyEnvelope,
        expectedEpoch: String? = knownEpoch(session)
    ) {
        storeEnvelope(
            session,
            "device",
            recipientDeviceId,
            createdByDeviceId,
            envelope, expectedEpoch
        )
    }

    suspend fun reserveSnapshot(
        session: PhoneAccountSession,
        snapshot: EncryptedCloudSnapshot,
        retentionDays: Int?,
        expectedEpoch: String? = knownEpoch(session)
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
                put("contentLayout", 3)
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
            }, expectedEpoch
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
                        signedUrl = item.optString("uploadUrl", item.optString("signedUrl")),
                        token = "", tusEndpoint = "", bucketName = "",
                        transport = item.getString("transport"),
                        uploadMethod = item.getString("uploadMethod"),
                        reservationId = item.optString("reservationId").takeIf { it.isNotBlank() }
                    )
                }
            )
        }
    }

    suspend fun refreshUpload(session: PhoneAccountSession, target: CloudUploadObject, expectedEpoch: String? = knownEpoch(session)): CloudUploadObject {
        val id = target.reservationId ?: return target
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        return post(session, "/functions/v1/cloud/uploads/$id/refresh", JSONObject(), expectedEpoch).use {
            val body = it.jsonBody()
            if (body.optBoolean("alreadyUploaded")) target.copy(alreadyUploaded = true)
            else target.copy(signedUrl = body.optString("uploadUrl", body.optString("signedUrl")),
                transport = body.getString("transport"), uploadMethod = body.getString("uploadMethod"))
        }
    }

    /** V2 metadata and ciphertext writes use the same owner/epoch fence. Never forwards a bearer to Storage. */
    suspend fun uploadCiphertext(session: PhoneAccountSession, target: CloudUploadObject, bytes: ByteArray, expectedEpoch: String) {
        require(target.transport == "epoch-proxy" && target.uploadMethod == "PUT") { "云上传服务需要升级" }
        require(bytes.size.toLong() == target.sizeBytes && CloudSnapshotCodec.sha256(bytes) == target.sha256) { "上传对象长度或哈希不匹配" }
        require(bytes.size <= 16 * 1024 * 1024)
        if (knownEpoch(session) != expectedEpoch) throw CloudTrustException()
        val id = requireNotNull(target.reservationId) { "缺少云上传预留" }
        require(java.util.UUID.fromString(id).toString() == id)
        val url = backendBaseUrl + "/functions/v1/cloud/uploads/$id"
        require(target.signedUrl == url) { "云上传地址不匹配" }
        val attempt = CloudUploadAttempt(id, onUploadTransportEvent)
        try {
            request(session, Request.Builder().url(url).put(bytes.toRequestBody("application/octet-stream".toMediaType())), expectedEpoch, attempt).close()
        } catch (error: IOException) {
            // Keep the existing service retry budget. Only its next PUT changes transport;
            // control requests, immutable bytes and both content fences remain unchanged.
            if (attempt.shouldUseHttp1(error)) http1Uploads = true
            throw error
        }
    }

    suspend fun completeSnapshot(
        session: PhoneAccountSession,
        snapshot: EncryptedCloudSnapshot,
        expectedEpoch: String? = knownEpoch(session)
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
            }, expectedEpoch
        ).close()
    }

    suspend fun snapshotHeads(session: PhoneAccountSession): List<CloudSnapshotHead> =
        get(session, "/functions/v1/cloud/snapshots/heads").use { response ->
            response.jsonBody().also { observeContentMetadata(session, it, response.request.tag(CloudContentRequestGeneration::class.java)) }.getJSONArray("heads").objects { it.snapshotHead() }
        }

    suspend fun snapshotHistory(session: PhoneAccountSession): List<CloudSnapshotHead> {
        val result = mutableListOf<CloudSnapshotHead>()
        var offset = 0L
        do {
            val next = get(session, "/functions/v1/cloud/snapshots/heads?history=true&limit=100&offset=$offset").use { response ->
                val json = response.jsonBody()
                observeContentMetadata(session, json, response.request.tag(CloudContentRequestGeneration::class.java))
                check(json.has("nextOffset")) { "历史快照功能需要更新云服务" }
                result += json.getJSONArray("heads").objects { it.snapshotHead() }
                if (json.isNull("nextOffset")) null else json.getLong("nextOffset")
            }
            if (next == null) break
            check(next > offset) { "历史快照分页无效" }
            offset = next
        } while (true)
        return result
    }

    suspend fun snapshot(
        session: PhoneAccountSession,
        snapshotId: String,
        manifestOnly: Boolean = false,
        chunk: String? = null
    ): CloudSnapshotDownload =
        get(session, "/functions/v1/cloud/snapshots/$snapshotId" + when {
            chunk != null -> { require(chunk.matches(Regex("[a-f0-9]{64}"))); "?chunk=$chunk" }
            manifestOnly -> "?manifestOnly=true"
            else -> ""
        }).use { response ->
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
        result: String = "applied",
        expectedEpoch: String? = knownEpoch(session)
    ) {
        post(
            session,
            "/functions/v1/cloud/snapshots/$snapshotId/ack",
            JSONObject().apply {
                put("deviceId", deviceId)
                put("result", result)
            }, expectedEpoch
        ).close()
    }

    suspend fun revokeDevice(session: PhoneAccountSession, deviceId: String, expectedEpoch: String? = knownEpoch(session)) {
        post(
            session,
            "/functions/v1/cloud/devices/$deviceId/revoke",
            JSONObject(), expectedEpoch
        ).close()
    }

    suspend fun resolveRssSource(session: PhoneAccountSession, sourceUrl: String): String =
        post(session, "/functions/v1/rss/sources/resolve", JSONObject().put("url", sourceUrl)).use {
            it.jsonBody().getJSONObject("source").getString("id").also { id -> require(id.matches(Regex("[A-Za-z0-9_-]+"))) }
        }

    suspend fun rssInventory(
        session: PhoneAccountSession,
        sourceUrl: String,
        mode: CloudRssInventoryMode
    ): ImportedRssSource {
        val sourceId = resolveRssSource(session, sourceUrl)
        val limit = if (mode == CloudRssInventoryMode.ALL) "all" else "128"
        return get(
            session,
            "/functions/v1/rss/sources/$sourceId/entries?limit=$limit"
        ).use { response ->
            val json = response.jsonBody()
            val source = json.getJSONObject("source")
            ImportedRssSource(
                url = sourceUrl,
                sourceId = sourceId,
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
                        guid = entry.nullableString("guid"),
                        contentHash = entry.nullableString("contentHash"),
                        dedupKey = entry.nullableString("dedupKey"),
                        identityLink = entry.nullableString("link")
                    )
                }
            )
        }
    }

    suspend fun deleteSnapshot(session: PhoneAccountSession, snapshotId: String, expectedEpoch: String? = knownEpoch(session)) {
        request(
            session,
            Request.Builder()
                .url(environment.backendBaseUrl + "/functions/v1/cloud/snapshots/$snapshotId")
                .delete(), expectedEpoch
        ).close()
    }

    suspend fun resetLibrary(session: PhoneAccountSession, expectedEpoch: String? = knownEpoch(session)): CloudLibraryResetResult =
        post(
            session,
            "/functions/v1/cloud/library/reset",
            JSONObject().apply { put("confirmation", RESET_LIBRARY_CONFIRMATION) }, expectedEpoch
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
            retryCloudObjectDownload(downloadBackoff) {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) response.throwHttp()
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (bytes.size.toLong()!=expectedSize || CloudSnapshotCodec.sha256(bytes)!=expectedSha256) throw CloudDownloadIntegrityException()
                    bytes
                }
            }
        }

    private suspend fun storeEnvelope(
        session: PhoneAccountSession,
        recipientType: String,
        recipientDeviceId: String?,
        createdByDeviceId: String,
        envelope: CloudKeyEnvelope,
        expectedEpoch: String? = knownEpoch(session)
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
            }, expectedEpoch
        ).close()
    }

    private suspend fun post(
        session: PhoneAccountSession,
        path: String,
        body: JSONObject,
        expectedEpoch: String? = knownEpoch(session)
    ): okhttp3.Response = request(
        session,
        Request.Builder()
            .url(environment.backendBaseUrl + path)
            .post(body.toString().toRequestBody(JSON)), expectedEpoch
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
        builder: Request.Builder,
        expectedEpoch: String? = null,
        uploadAttempt: CloudUploadAttempt? = null
    ): okhttp3.Response = withContext(Dispatchers.IO) {
        checkSession(session)
        require(environment.backendBaseUrl.isNotBlank()) { "云服务地址未配置" }
        require(!session.isExpired) { "登录已过期，请重新登录" }
        // Capture once, including an authentication refresh. Never retag stale work with a new generation.
        val fence = kotlinx.coroutines.currentCoroutineContext()[CloudContentGenerationFence]
        val expectedGeneration = if (fence?.scope == scopeId(session)) fence.generation else knownContentGeneration(session)
        fun buildRequest() = builder
            .tag(CloudContentRequestGeneration::class.java, CloudContentRequestGeneration(expectedGeneration))
            .also { checkSession(session) }
            .withWatchRssAppVersionHeader()
            .header("authorization", "Bearer ${session.accessToken}")
            .apply {
                deviceAccessToken()?.takeIf { it.isNotBlank() }?.let {
                    header("x-watchrss-device-authorization", "Bearer $it")
                }
            }
            .apply {
                val request = build()
                if (request.method != "GET" && request.url.encodedPath.startsWith("/functions/v1/cloud/") &&
                    request.url.encodedPath != "/functions/v1/cloud/bootstrap") {
                    header("x-watchrss-library-epoch", requireNotNull(expectedEpoch) { "请先读取云资料库纪元" })
                }
            }
            .apply {
                val request = build()
                if (isCloudContentWrite(request.method, request.url.encodedPath)) {
                    header("x-watchrss-content-generation", expectedGeneration
                        ?: throw CloudHttpException(426, "{\"error\":\"client_upgrade_required\"}"))
                }
            }
            .apply { currentDeviceId?.let { header("x-watchrss-cloud-device-id", it) } }
            .header("apikey", environment.supabaseAnonKey)
            .header("accept", "application/json")
            .build()
        val firstRequest = buildRequest()
        // Reserve/complete may verify hundreds of stored immutable blocks.
        val requestHttp = http.newBuilder().followRedirects(false).followSslRedirects(false).apply {
            if (uploadAttempt != null) {
                // OkHttp otherwise replays a 408 before our refresh/integrity-aware
                // retry loop can observe it, doubling slow body attempts invisibly.
                retryOnConnectionFailure(false)
                eventListener(uploadAttempt)
                if (http1Uploads) protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            }
            if (firstRequest.url.encodedPath in setOf("/functions/v1/cloud/snapshots/reserve", "/functions/v1/cloud/snapshots/complete") ||
                (firstRequest.method == "PUT" && firstRequest.url.encodedPath.startsWith("/functions/v1/cloud/uploads/"))) {
                readTimeout(180, TimeUnit.SECONDS); writeTimeout(180, TimeUnit.SECONDS)
            }
        }.build()
        var response = requestHttp.newCall(firstRequest).execute()
        val rejected = firstRequest.header("x-watchrss-device-authorization")?.removePrefix("Bearer ")
        if (response.code == 403 && rejected != null && isRefreshableCloudDeviceSessionError(
                runCatching { JSONObject(response.peekBody(4096).string()).optString("error") }.getOrDefault("")
            )) {
            response.close()
            if (refreshDeviceSession(rejected, session.userId)) {
                response = requestHttp.newCall(buildRequest()).execute()
            } else {
                throw CloudDeviceAuthorizationException()
            }
        }
        if (response.code == 403) {
            val code = runCatching { JSONObject(response.peekBody(4096).string()).optString("error") }.getOrDefault("")
            if (code in setOf("device_session_revoked", "device_session_expired", "device_session_invalid", "device_revoked", "device_mismatch")) {
                response.close()
                throw CloudDeviceAuthorizationException()
            }
        }
        response.also {
            if (it.code == 409 && runCatching { JSONObject(it.peekBody(4096).string()).optString("error") }.getOrDefault("") == "device_key_conflict") onDeviceKeyConflict(session)
            if (it.code == 409 && runCatching { JSONObject(it.peekBody(4096).string()).optString("error") }.getOrDefault("") == "library_epoch_mismatch") {
                onEpochMismatch(session)
                epochs.remove(scopeId(session))
            }
            if (!it.isSuccessful) it.throwHttp()
        }
    }

    private fun okhttp3.Response.throwHttp(): Nothing {
        val detail = body?.string().orEmpty()
        close()
        throw CloudHttpException(code, detail.ifBlank { message })
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
        revokedAt = nullableString("revokedAt"),
        attestation = nullableString("attestation"),
        attestationKeyVersion = if (isNull("attestationKeyVersion")) null else optInt("attestationKeyVersion"),
        watchSelfRegisteredAt = nullableString("watchSelfRegisteredAt")
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
        observedHeads = getJSONObject("observedHeads").longMap(),
        completedAt = nullableString("completedAt"),
        totalSizeBytes = optLong("totalSizeBytes", getLong("manifestSizeBytes")),
        contentLayout = if (has("contentLayout") && !isNull("contentLayout")) getInt("contentLayout") else null
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

internal fun isRefreshableCloudDeviceSessionError(code: String): Boolean =
    code == "device_session_revoked" || code == "device_session_expired"

internal class CloudDeviceAuthorizationException : IOException("设备身份需要重新验证，请前往账号页面完成验证")

internal class CloudHttpException(val status: Int, val detail: String) : IOException(if (status == 426) "请升级客户端后继续云同步" else if (status == 409 && runCatching { JSONObject(detail).optString("error") }.getOrDefault("") == "device_key_conflict") "本机设备身份冲突，请重新登记本机" else "HTTP $status: $detail")
