package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

import android.content.Context
import android.os.Build
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.data.backup.BackupImportMode
import com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupService
import com.lightningstudio.watchrss.phone.data.note.NoteCloudStateCodec
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

enum class CloudSyncPhase {
    IDLE,
    PAUSED,
    CHECKING,
    NEEDS_RECOVERY,
    WAITING_FOR_WIFI,
    DOWNLOADING,
    MERGING,
    UPLOADING,
    COMPLETE,
    ERROR
}

data class PhoneCloudSyncState(
    val phase: CloudSyncPhase = CloudSyncPhase.IDLE,
    val message: String = "",
    val member: CloudMemberState? = null,
    val uploadedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val lastCompletedAt: Long = 0
)

data class PhoneCloudSyncResult(
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    val snapshotsApplied: Int,
    val snapshotUploaded: Boolean,
    val pendingArticleStates: Int
)

data class CloudTransferEstimate(
    val uploadBytes: Long,
    val downloadBytes: Long
)

class PhoneCloudSyncService(
    context: Context,
    private val accountRepository: PhoneAccountRepository,
    private val backupService: WatchRssBackupService,
    private val repository: PhoneCompanionRepository,
    private val noteRepository: NoteRepository,
    private val deviceId: String,
    private val client: PhoneCloudClient,
    private val keyManager: CloudKeyManager = CloudKeyManager(context),
    val settings: PhoneCloudStateStore = PhoneCloudStateStore(context),
    private val cache: CloudLocalCache = CloudLocalCache(context),
    private val uploader: SupabaseTusUploader = SupabaseTusUploader(context),
    private val networkGate: CloudNetworkGate = CloudNetworkGate(context),
    private val rssInventoryPreferences: CloudRssInventoryPreferences =
        CloudRssInventoryPreferences(context),
    private val codec: CloudSnapshotCodec = CloudSnapshotCodec(),
    private val legacyDeviceId: String? = null
) {
    private val debugTrace = java.io.File(context.cacheDir, "cloud-sync-debug.trace")
    private fun traceSync(stage: String) {
        if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG) runCatching {
            if (debugTrace.length() > 65536) debugTrace.writeText("")
            debugTrace.appendText("${System.currentTimeMillis()} $stage\n")
        }
    }
    private val recordStore = PhoneCloudRecordStore(context.applicationContext)
    private val recordSync = PhoneCloudRecordSync(client, recordStore,
        backupService::cloudRecordPages, { session, kind, value ->
            backupService.applyCloudRecord(kind, value) { checkApprovalOwner(session) }
        },
        keyManager::currentKeyVersion, keyManager::getAccountKey, ::checkApprovalOwner)
    private val noteTransfer = NoteImportExportService(context, noteRepository)
    private val syncMutex = CloudSyncSingleFlight()
    private val _state = MutableStateFlow(PhoneCloudSyncState())
    val state: StateFlow<PhoneCloudSyncState> = _state

    private val pauseGate = CloudSyncPauseGate(settings.syncPaused) { settings.syncPaused = it }
    val paused: StateFlow<Boolean> = pauseGate.state

    /** Finish the current snapshot atomically; no subsequent snapshot/transfer starts. */
    fun setSyncPaused(value: Boolean) {
        pauseGate.setPaused(value)
        if (!syncMutex.isLocked) {
            update(if (value) CloudSyncPhase.PAUSED else CloudSyncPhase.IDLE,
                if (value) "已暂停同步" else "等待同步")
        }
    }

    /** Quota-only request: never starts a backup, download or approval flow. */
    suspend fun refreshDashboardMembership(): CloudMemberState {
        val session = requireSession()
        val member = client.membership(session)
        if (accountRepository.session.value?.userId == session.userId) {
            _state.update { it.copy(member = member) }
        }
        return member
    }

    suspend fun loadMembership(): CloudMemberState = refreshDashboardMembership()

    fun hasLocalAccountKey(): Boolean =
        accountRepository.session.value?.let { keyManager.hasAccountKey(it.userId) } == true

    fun rssInventoryMode(sourceUrl: String): CloudRssInventoryMode =
        rssInventoryPreferences.mode(sourceUrl)

    fun setRssInventoryMode(sourceUrl: String, mode: CloudRssInventoryMode) {
        rssInventoryPreferences.setMode(sourceUrl, mode)
    }

    suspend fun estimateManualSync(): CloudTransferEstimate {
        val session = requireSession()
        require(keyManager.hasAccountKey(session.userId)) { "当前设备尚未获得云端解密密钥" }
        val heads = client.snapshotHeads(session)
        var downloadBytes = 0L
        heads.filter {
            it.deviceSequence > settings.appliedSequence(it.sourceDeviceId, full = true)
        }.forEach { head ->
            val snapshot = client.snapshot(session, head.id)
            downloadBytes += head.manifestSizeBytes
            downloadBytes += snapshot.chunks.sumOf(CloudDownloadObject::sizeBytes)
        }
        val privateArchive = backupService.createCloudPrivateArchiveFile()
        val rssState = backupService.createCloudRssState()
        val uploadBytes = privateArchive.length() +
            rssState.size +
            128 * 1024L
        return CloudTransferEstimate(uploadBytes, downloadBytes)
    }

    suspend fun loadBootstrap(): CloudBootstrap {
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        checkApprovalOwner(session)
        restoreLegacyDeviceEnvelopes(session, bootstrap)
        ensureApprovalIdentity(session, bootstrap)
        return client.bootstrap(session).also {
            checkApprovalOwner(session)
            // Loading account/authorization information must not erase transfer or completion state.
            val member = it.member
            _state.update { current -> current.copy(member = member) }
        }
    }

    suspend fun listSnapshotHeads(): List<CloudSnapshotHead> =
        client.snapshotHeads(requireSession())

    suspend fun listSnapshotHistory(): List<CloudSnapshotHead> =
        client.snapshotHistory(requireSession())

    suspend fun inspectSnapshot(snapshotId: String): CloudSnapshotRestorePreview {
        val session = requireSession()
        val decoded = downloadSnapshotObjects(session, snapshotId)
        val privatePreview = decoded.objects[PRIVATE_LIBRARY_OBJECT]
            ?.let(backupService::inspectCloudPrivateLibrary)
        val statePreview = decoded.objects[RSS_STATE_OBJECT]
            ?.let(backupService::inspectCloudRssState)
            ?: (0 to 0)
        val relayPreview = decoded.objects[RELAY_LIBRARY_OBJECT]
            ?.let(backupService::inspectCloudRelayLibrary)
            ?: (0 to 0)
        return CloudSnapshotRestorePreview(
            snapshotId = decoded.download.head.id,
            sourceDeviceId = decoded.download.head.sourceDeviceId,
            deviceSequence = decoded.download.head.deviceSequence,
            sourceCount = maxOf(
                privatePreview?.sourceCount ?: 0,
                statePreview.first,
                relayPreview.first
            ),
            privateArticleCount = privatePreview?.articleCount ?: 0,
            relayArticleCount = relayPreview.second,
            rssStateCount = statePreview.second,
            encryptedDownloadBytes = decoded.download.head.manifestSizeBytes +
                decoded.download.chunks.sumOf(CloudDownloadObject::sizeBytes),
            hasPrivateArchive = privatePreview != null
        )
    }

    suspend fun restoreSnapshot(
        snapshotId: String,
        mode: BackupImportMode = BackupImportMode.MERGE
    ): CloudSnapshotRestoreResult = syncMutex.withLock {
        val session = requireSession()
        val decoded = downloadSnapshotObjects(session, snapshotId)
        val privateArchive = decoded.objects[PRIVATE_LIBRARY_OBJECT]
        require(mode != BackupImportMode.REPLACE || privateArchive != null) {
            "该手表快照没有完整私有归档，不能执行覆盖恢复"
        }
        if (mode == BackupImportMode.MERGE) backupService.createSafetySnapshot()
        update(CloudSyncPhase.MERGING, "正在恢复云快照")
        val privateResult = privateArchive?.let {
            backupService.restorePrivateLibrary(it, mode)
        }
        val relayChanged = decoded.objects[RELAY_LIBRARY_OBJECT]
            ?.let { backupService.restoreCloudRelayLibrary(it) }
            ?: 0
        val stateResult = decoded.objects[RSS_STATE_OBJECT]
            ?.let { backupService.applyCloudRssState(it, restoreAsNewChange = true) }
            ?: (0 to 0)
        settings.markApplied(
            decoded.download.head.sourceDeviceId,
            decoded.download.head.deviceSequence,
            full = true
        )
        settings.markApplied(
            decoded.download.head.sourceDeviceId,
            decoded.download.head.deviceSequence,
            full = false
        )
        client.acknowledge(session, snapshotId, deviceId)
        update(
            CloudSyncPhase.COMPLETE,
            if (mode == BackupImportMode.REPLACE) {
                "覆盖恢复完成，已先创建本地安全快照"
            } else {
                "合并恢复完成"
            },
            completedAt = System.currentTimeMillis()
        )
        CloudSnapshotRestoreResult(
            privateArticlesChanged = privateResult?.changedArticleCount ?: 0,
            sourcesChanged = privateResult?.changedSourceCount ?: 0,
            relayItemsChanged = relayChanged,
            rssStatesApplied = stateResult.first,
            rssStatesPending = stateResult.second
        )
    }

    suspend fun deleteSnapshot(snapshotId: String) {
        val session = requireSession()
        client.deleteSnapshot(session, snapshotId)
        cache.deleteSnapshot(session.userId, snapshotId)
    }

    suspend fun resetCloudLibrary(confirmationPhrase: String): CloudLibraryResetResult =
        syncMutex.withLock {
            require(isCloudLibraryDeleteConfirmed(confirmationPhrase)) {
                "请输入“$CLOUD_LIBRARY_DELETE_PHRASE”确认"
            }
            val session = requireSession()
            update(CloudSyncPhase.CHECKING, "正在永久删除云端资料库")
            val result = client.resetLibrary(session)
            try {
                withContext(Dispatchers.IO) {
                    keyManager.clearAccountKeys(session.userId)
                    settings.clearLibraryState()
                    cache.clear(session.userId)
                    recordStore.clear(session.userId)
                }
            } catch (error: Exception) {
                update(CloudSyncPhase.ERROR, "云端资料库已删除，但本机云状态清理失败")
                throw IllegalStateException(
                    "云端资料库已删除，但本机云状态清理失败：${error.message.orEmpty()}",
                    error
                )
            }
            update(CloudSyncPhase.IDLE, "云端资料库已删除，本机资料仍保留")
            result
        }

    suspend fun prepareFirstDevice(): RecoveryKeySetup {
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        require(bootstrap.member.writable) { "当前会员状态不能启用云备份" }
        require(bootstrap.keyEnvelopes.none { it.recipientType == "recovery" }) {
            "账号已有恢复密钥，请使用原24词恢复"
        }
        return keyManager.createRecoverySetup(session.userId)
    }

    suspend fun activateFirstDevice(
        setup: RecoveryKeySetup,
        confirmedWords: List<String>
    ) {
        require(setup.words.map { it.lowercase() } == confirmedWords.map { it.trim().lowercase() }) {
            "24词恢复密钥确认不一致"
        }
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        require(bootstrap.member.writable) { "当前会员状态不能启用云备份" }
        if (
            isCloudKeySetupComplete(
                envelopes = bootstrap.keyEnvelopes,
                devices = bootstrap.devices,
                deviceId = deviceId,
                keyVersion = setup.envelope.keyVersion
            )
        ) {
            return
        }
        require(bootstrap.keyEnvelopes.none { it.recipientType == "recovery" }) {
            "账号已存在恢复密钥信封，不能覆盖"
        }
        registerCurrentDevice(session)
        client.storeRecoveryEnvelope(session, deviceId, setup.envelope)
        storeEnvelopeForCurrentDevice(session)
    }

    suspend fun recoverWithWords(words: List<String>) {
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        val recovery = bootstrap.keyEnvelopes
            .filter { it.recipientType == "recovery" }
            .map(StoredCloudKeyEnvelope::envelope)
        keyManager.recoverWithWords(session.userId, words, recovery)
        registerCurrentDevice(session)
        storeEnvelopeForCurrentDevice(session)
    }

    private fun checkApprovalOwner(session: PhoneAccountSession) {
        val current=accountRepository.session.value
        check(current?.userId==session.userId && current.accessToken==session.accessToken) { "账号已切换，请重新操作" }
    }

    suspend fun beginCodeApproval(): String {
        val session=requireSession()
        requestDeviceApproval();checkApprovalOwner(session)
        return client.approvalRequest(session,deviceId).also { checkApprovalOwner(session) }
    }

    suspend fun redeemCodeApproval(id: String, code: String): Boolean {
        require(code.matches(Regex("[0-9]{8}"))) { "请输入旧设备显示的 8 位配对码" }
        val session=requireSession()
        client.redeemApproval(session,id,deviceId,code);checkApprovalOwner(session)
        return acceptDeviceApproval().also { checkApprovalOwner(session) }
    }

    suspend fun pendingCodeApprovals(): List<org.json.JSONObject> {
        val session=requireSession()
        if(!keyManager.hasAccountKey(session.userId))return emptyList()
        if(!client.membership(session).writable)return emptyList()
        checkApprovalOwner(session)
        ensureApprovalIdentity(session, client.bootstrap(session))
        return client.pendingApprovals(session,deviceId).also { checkApprovalOwner(session) }
    }

    suspend fun offerCodeApproval(target: org.json.JSONObject, code: String) = withContext(Dispatchers.IO) {
        val session=requireSession()
        val publicKey=CloudKeyEnvelopeCodec.decodeP256PublicKey(target.getString("publicKeySpki"))
        val envelopes=keyManager.keyVersions(session.userId).map { version ->
            val key=keyManager.getAccountKey(session.userId,version) ?: error("本机缺少云密钥")
            try { CloudKeyEnvelopeCodec.createDeviceEnvelope(key,session.userId,target.getString("deviceId"),publicKey,version) }
            finally { key.fill(0) }
        }
        checkApprovalOwner(session)
        client.offerApproval(session,target.getString("id"),deviceId,code,envelopes)
        checkApprovalOwner(session)
    }

    suspend fun requestDeviceApproval(): CloudBootstrap {
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        require(bootstrap.member.readable) { "当前账号不能读取会员云空间" }
        client.registerDevice(
            session = session,
            deviceId = deviceId,
            displayName = "手机 · ${Build.MODEL}",
            publicKeySpki = keyManager.devicePublicKeySpki(session.userId, deviceId),
            keyVersion = deviceApprovalKeyVersion(bootstrap)
        )
        return client.bootstrap(session).also {
            update(CloudSyncPhase.NEEDS_RECOVERY, "等待旧设备批准此手机", it.member)
        }
    }

    suspend fun acceptDeviceApproval(): Boolean {
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        checkApprovalOwner(session)
        restoreLegacyDeviceEnvelopes(session, bootstrap)
        deviceApprovalEnvelopes(bootstrap, deviceId).forEach { stored ->
            keyManager.unwrapDeviceEnvelope(session.userId, deviceId, stored.envelope)
        }
        val approved = keyManager.hasAccountKey(session.userId)
        update(
            if (approved) CloudSyncPhase.IDLE else CloudSyncPhase.NEEDS_RECOVERY,
            if (approved) "旧设备批准成功，当前手机已获得解密能力" else "仍在等待旧设备批准",
            bootstrap.member
        )
        return approved
    }

    suspend fun approveDevice(recipientDeviceId: String) {
        val session = requireSession()
        val bootstrap = client.bootstrap(session)
        val target = bootstrap.devices.firstOrNull {
            it.deviceId == recipientDeviceId && it.revokedAt == null
        } ?: error("待授权设备不存在或已撤销")
        require(target.publicKeySpki.isNotBlank()) { "待授权设备没有有效公钥" }
        val publicKey = CloudKeyEnvelopeCodec.decodeP256PublicKey(target.publicKeySpki)
        keyManager.keyVersions(session.userId).forEach { version ->
            val accountKey = keyManager.getAccountKey(session.userId, version)
                ?: error("当前手机缺少第${version}版账号密钥")
            client.storeDeviceEnvelope(
                session,
                recipientDeviceId,
                deviceId,
                CloudKeyEnvelopeCodec.createDeviceEnvelope(
                    accountKey = accountKey,
                    userId = session.userId,
                    recipientDeviceId = recipientDeviceId,
                    recipientPublicKey = publicKey,
                    keyVersion = version
                )
            )
        }
    }

    suspend fun rotateAccountKey(words: List<String>): Int {
        val session = requireSession()
        registerCurrentDevice(session)
        val bootstrap = client.bootstrap(session)
        require(bootstrap.member.writable) { "当前会员状态不能轮换密钥" }
        val recoveryEnvelopes = bootstrap.keyEnvelopes
            .filter { it.recipientType == "recovery" }
            .map(StoredCloudKeyEnvelope::envelope)
        require(recoveryEnvelopes.isNotEmpty()) { "账号没有恢复密钥信封" }
        val entropy = RecoveryWords.decode(words)
        try {
            val latestRecovery = recoveryEnvelopes.maxBy(CloudKeyEnvelope::keyVersion)
            val verified = CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(
                latestRecovery,
                session.userId,
                entropy
            )
            try {
                val localLatest = keyManager.getAccountKey(
                    session.userId,
                    latestRecovery.keyVersion
                ) ?: error("当前手机缺少最新版账号密钥")
                require(verified.contentEquals(localLatest)) { "恢复词与当前账号密钥不匹配" }
            } finally {
                verified.fill(0)
            }

            val newVersion = maxOf(
                recoveryEnvelopes.maxOf(CloudKeyEnvelope::keyVersion),
                keyManager.currentKeyVersion(session.userId)
            ) + 1
            val newKey = ByteArray(32).also(SecureRandom()::nextBytes)
            client.storeRecoveryEnvelope(
                session,
                deviceId,
                CloudKeyEnvelopeCodec.createRecoveryEnvelope(
                    newKey,
                    session.userId,
                    newVersion,
                    entropy
                )
            )
            keyManager.saveAccountKey(
                session.userId,
                newVersion,
                newKey,
                makeCurrent = true
            )
            bootstrap.devices.filter { it.revokedAt == null && it.publicKeySpki.isNotBlank() }
                .forEach { target ->
                    client.storeDeviceEnvelope(
                        session,
                        target.deviceId,
                        deviceId,
                        CloudKeyEnvelopeCodec.createDeviceEnvelope(
                            accountKey = newKey,
                            userId = session.userId,
                            recipientDeviceId = target.deviceId,
                            recipientPublicKey = CloudKeyEnvelopeCodec.decodeP256PublicKey(
                                target.publicKeySpki
                            ),
                            keyVersion = newVersion
                        )
                    )
                }
            registerCurrentDevice(session)
            return newVersion
        } finally {
            entropy.fill(0)
        }
    }

    suspend fun revokeDevice(deviceId: String) {
        require(deviceId != this.deviceId) { "不能在当前设备上撤销自己" }
        client.revokeDevice(requireSession(), deviceId)
    }

    private val readingPriority = CloudReadingPriority()
    private val readingManifests = object : LinkedHashMap<String, CloudSnapshotManifest>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CloudSnapshotManifest>?) = size > 4
    }

    /** Foreground reads bypass the bulk-sync lock. Bulk transfers yield between chunks. */
    suspend fun loadArticleForReading(articleId: String): com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity? =
        withContext(Dispatchers.IO) {
            val article = repository.getArticle(articleId) ?: return@withContext null
            val legacyFlatEpub = article.url.contains("/import-epub/") && article.contentHtml.isNullOrBlank() &&
                article.contentText.isNotBlank() && !article.contentText.contains('\n') && article.contentText.length > 280 &&
                !com.lightningstudio.watchrss.phone.data.local.isArticleContentMarker(article.contentText)
            if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG) android.util.Log.d("WatchRssBody", "open textChars=${article.contentText.length} html=${!article.contentHtml.isNullOrBlank()} legacyFlat=$legacyFlatEpub deleted=${article.deleted}")
            if ((!legacyFlatEpub && (article.contentText.isNotBlank() || !article.contentHtml.isNullOrBlank())) || article.deleted) return@withContext article
            if (!com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.isImportedContentUrl(article.url)) return@withContext article
            val session = requireSession()
            readingPriority.foreground {
                try {
                    restoreArticleBody(session, article, readingHeads(session), foreground = true)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG) android.util.Log.w("WatchRssBody", "reading failed: ${error.javaClass.simpleName}" + if (error is CloudHttpException) " status=${error.status}" else "")
                    if (!legacyFlatEpub) throw error
                }
                checkApprovalOwner(session)
                repository.getArticle(articleId)
            }
        }

    // The normal native heads endpoint deliberately hides reader projections.
    // The authorized history endpoint includes them; keep only each device's latest head.
    private suspend fun readingHeads(session: PhoneAccountSession): List<CloudSnapshotHead> =
        client.snapshotHistory(session).groupBy { it.sourceDeviceId }.values.map { rows -> rows.maxBy { it.deviceSequence } }

    private suspend fun yieldToReading() {
        readingPriority.awaitBackgroundTurn()
    }

    private suspend fun restoreArticleBody(
        session: PhoneAccountSession,
        article: com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity,
        heads: List<CloudSnapshotHead>,
        foreground: Boolean = false
    ) {
        val hash = CloudSnapshotCodec.sha256(article.articleId.toByteArray()).take(32)
        val version = CloudSnapshotCodec.sha256((article.contentHash.ifBlank { article.updatedAt.toString() }).toByteArray()).take(32)
        val name = "reader/$hash/$version.txt"
        for (head in heads.sortedWith(compareByDescending<CloudSnapshotHead> { it.sourceDeviceId.startsWith("reader-migration-") }.thenByDescending { it.completedAt ?: "" })) {
            if (!foreground) yieldToReading()
            checkApprovalOwner(session)
            val key = keyManager.getAccountKey(session.userId, head.keyVersion) ?: continue
            val cacheKey = "${session.userId}:${head.id}"
            val manifest = synchronized(readingManifests) { readingManifests[cacheKey] } ?: run {
                val encrypted = cache.loadManifest(session.userId, head.id) ?: run {
                    val remote = client.snapshot(session, head.id, manifestOnly = true)
                    client.download(remote.manifestSignedUrl, head.manifestSizeBytes, head.manifestSha256)
                }
                codec.decryptManifest(key, head.id, encrypted).also {
                    checkApprovalOwner(session)
                    synchronized(readingManifests) { readingManifests[cacheKey] = it }
                }
            }
            if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG && foreground) android.util.Log.d("WatchRssBody", "manifest projection=${head.sourceDeviceId.startsWith("reader-migration-")} total=${manifest.objects.size} versions=${manifest.objects.count { it.name.startsWith("reader/$hash/") }} exact=${manifest.objects.any { it.name == name }}")
            val descriptor = manifest.objects.firstOrNull { it.name == name } ?: continue
            if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG && foreground) android.util.Log.d("WatchRssBody", "matched chapter chunks=${descriptor.chunks.size} bytes=${descriptor.originalBytes}")
            require(descriptor.originalBytes in 0..16L * 1024 * 1024 && descriptor.chunks.size <= 4096) { "章节过大，请稍后通过完整同步恢复" }
            val chunks = mutableMapOf<String, ByteArray>()
            for (chunk in descriptor.chunks) {
                if (!foreground) yieldToReading()
                checkApprovalOwner(session)
                require(chunk.ciphertextBytes in 1..(17 * 1024 * 1024))
                val encrypted = cache.loadChunk(session.userId, chunk.ciphertextSha256) ?: run {
                    val remote = client.snapshot(session, head.id, chunk = chunk.ciphertextSha256)
                        .chunks.single { it.sha256 == chunk.ciphertextSha256 }
                    require(remote.sizeBytes == chunk.ciphertextBytes.toLong())
                    client.download(remote.signedUrl, remote.sizeBytes, remote.sha256)
                }
                chunks[chunk.ciphertextSha256] = encrypted
            }
            val text = codec.restoreObjects(key, manifest.copy(objects = listOf(descriptor))) { chunks.getValue(it) }
                .getValue(name).toString(Charsets.UTF_8)
            checkApprovalOwner(session)
            if (article.contentText.isNotEmpty()) {
                val whitespace = Regex("\\s+")
                require(article.contentText.replace(whitespace, "") == text.replace(whitespace, "")) { "正文版本不一致" }
            }
            repository.fillMissingCloudBody(article, text) { checkApprovalOwner(session) }
            return
        }
        error("正文尚未同步完成，请稍后重试；后台将继续恢复")
    }

    suspend fun syncNow(manual: Boolean = false): PhoneCloudSyncResult = withContext(Dispatchers.IO) {
        traceSync("sync-request")
        syncMutex.run(manual, { PhoneCloudSyncResult(0, 0, 0, false, 0) }) {
            traceSync("sync-lock")
            var uploadedBytes = 0L
            var downloadedBytes = 0L
            var applied = 0
            var pendingStates = 0
            var uploaded = false
            fun pausedResult(): PhoneCloudSyncResult? {
                if (!paused.value) return null
                update(CloudSyncPhase.PAUSED, "已暂停同步")
                return PhoneCloudSyncResult(uploadedBytes, downloadedBytes, applied, uploaded, pendingStates)
            }
            pausedResult()?.let { return@run it }
            try {
                val localSequenceAtStart = repository.getLibrarySyncCursor(null).localMaxSeq
                update(CloudSyncPhase.CHECKING, "正在检查云端")
                require(networkGate.isConnected()) { "当前没有可用网络" }
                val session = requireSession()
                cache.prune(session.userId)
                var bootstrap = client.bootstrap(session)
                pausedResult()?.let { return@run it }
                if (!keyManager.hasAccountKey(session.userId)) {
                    bootstrap = requestDeviceApproval()
                    if (!acceptDeviceApproval()) {
                        error("当前设备尚未获得云端解密密钥，请在旧设备批准后重试")
                    }
                    bootstrap = client.bootstrap(session)
                }
                if (bootstrap.member.writable) {
                    registerCurrentDevice(session)
                    bootstrap = client.bootstrap(session)
                }
                val fullTransfer = networkGate.allowsPrivateBodies(
                    settings.networkPolicy,
                    manual
                )
                if (
                    !manual &&
                    settings.networkPolicy == CloudNetworkPolicy.WIFI_AND_CHARGING &&
                    !fullTransfer
                ) {
                    update(
                        CloudSyncPhase.WAITING_FOR_WIFI,
                        "等待Wi‑Fi并充电后同步",
                        bootstrap.member
                    )
                    return@run PhoneCloudSyncResult(0, 0, 0, false, 0)
                }
                pausedResult()?.let { return@run it }
                settings.ensureFullBodyRestoreVersion(session.userId)
                val heads = client.snapshotHeads(session)
                for (head in heads.sortedBy(CloudSnapshotHead::deviceSequence)) {
                    pausedResult()?.let { return@run it }
                    if (head.sourceDeviceId.startsWith("reader-migration-")) continue
                    if (head.sourceDeviceId == deviceId &&
                        head.deviceSequence <= settings.appliedSequence(deviceId, fullTransfer)
                    ) {
                        continue
                    }
                    val alreadyApplied = settings.appliedSequence(
                        head.sourceDeviceId,
                        full = fullTransfer
                    )
                    if (head.deviceSequence <= alreadyApplied) continue
                    update(CloudSyncPhase.DOWNLOADING, "正在下载 ${head.sourceDeviceId}", bootstrap.member)
                    val outcome = pullSnapshot(
                        session = session,
                        accountKey = keyManager.getAccountKey(session.userId, head.keyVersion)
                            ?: error("缺少第${head.keyVersion}版账号密钥，请使用恢复词重新授权"),
                        head = head,
                        fullTransfer = fullTransfer
                    )
                    downloadedBytes += outcome.downloadedBytes
                    pendingStates += outcome.pendingStates
                    if (outcome.complete) {
                        settings.markApplied(head.sourceDeviceId, head.deviceSequence, full = true)
                        settings.markApplied(head.sourceDeviceId, head.deviceSequence, full = false)
                        client.acknowledge(session, head.id, deviceId)
                    } else if (outcome.stateApplied) {
                        settings.markApplied(head.sourceDeviceId, head.deviceSequence, full = false)
                    }
                    if (outcome.changed) applied += 1
                }

                pausedResult()?.let { return@run it }
                if (fullTransfer && bootstrap.member.readable) {
                    update(
                        CloudSyncPhase.DOWNLOADING,
                        "正在更新公共 RSS 库",
                        bootstrap.member,
                        uploadedBytes,
                        downloadedBytes
                    )
                    refreshRssInventory(session)
                }

                pausedResult()?.let { return@run it }
                val compacted = repository.compactCloudExcerpts { checkApprovalOwner(session) }
                traceSync("metadata-compacted:$compacted")
                recordSync.sync(session, bootstrap.member.writable)
                if (fullTransfer) {
                    val missingBodies = repository.missingCloudBodyIds()
                    val bodyHeads = if (missingBodies.isEmpty()) emptyList() else readingHeads(session)
                    for (articleId in missingBodies) {
                        pausedResult()?.let { return@run it }
                        yieldToReading()
                        val article = repository.getArticle(articleId) ?: continue
                        if (article.contentText.isNotBlank() || !article.contentHtml.isNullOrBlank()) continue
                        update(CloudSyncPhase.DOWNLOADING, "正在恢复文章正文", bootstrap.member)
                        restoreArticleBody(session, article, bodyHeads)
                    }
                    check(repository.missingCloudBodyIds().isEmpty()) { "仍有正文尚未恢复，后台将继续同步" }
                }
                pausedResult()?.let { return@run it }
                if (bootstrap.member.writable) {
                    val currentKeyVersion = keyManager.currentKeyVersion(session.userId)
                    val accountKey = keyManager.getAccountKey(session.userId, currentKeyVersion)
                        ?: error("当前账号主密钥不存在")
                    val freshHeads = client.snapshotHeads(session)
                    checkApprovalOwner(session)
                    check(freshHeads.filterNot { it.sourceDeviceId.startsWith("reader-migration-") }.all {
                        it.deviceSequence <= settings.appliedSequence(it.sourceDeviceId, fullTransfer)
                    }) { "云端资料仍有更新待合并，将继续同步" }
                    val uploadResult = uploadSnapshot(
                        session = session,
                        accountKey = accountKey,
                        bootstrap = bootstrap,
                        heads = freshHeads,
                        fullTransfer = fullTransfer,
                        keyVersion = currentKeyVersion
                    )
                    uploadedBytes += uploadResult.bytes
                    uploaded = uploadResult.uploaded
                }
                if (!fullTransfer) {
                    update(
                        CloudSyncPhase.WAITING_FOR_WIFI,
                        "小状态已同步；正文等待Wi‑Fi",
                        bootstrap.member,
                        uploadedBytes,
                        downloadedBytes
                    )
                } else if (pendingStates > 0 || localSequenceAtStart != repository.getLibrarySyncCursor(null).localMaxSeq) {
                    update(CloudSyncPhase.DOWNLOADING, "仍有本机改动待合并，将继续同步", bootstrap.member, uploadedBytes, downloadedBytes)
                } else {
                    update(
                        CloudSyncPhase.COMPLETE,
                        "云同步完成",
                        bootstrap.member,
                        uploadedBytes,
                        downloadedBytes,
                        System.currentTimeMillis()
                    )
                    traceSync("sync-complete:pending=$pendingStates")
                }
                cache.prune(session.userId)
                pausedResult()?.let { return@run it }
                PhoneCloudSyncResult(
                    uploadedBytes = uploadedBytes,
                    downloadedBytes = downloadedBytes,
                    snapshotsApplied = applied,
                    snapshotUploaded = uploaded,
                    pendingArticleStates = pendingStates
                )
            } catch (error: CancellationException) {
                traceSync("cancelled")
                update(CloudSyncPhase.IDLE, "等待继续同步")
                throw error
            } catch (error: Throwable) {
                traceSync("failure:${error.javaClass.simpleName}:status=${(error as? CloudHttpException)?.status ?: (error as? CloudObjectUploadException)?.status}:${error.stackTrace.firstOrNull { it.className.startsWith("com.lightningstudio.watchrss.phone") }}")
                if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG) android.util.Log.w("WatchRssCloud", "sync failed phase=${_state.value.phase} type=${error.javaClass.simpleName} at=${error.stackTrace.firstOrNull { it.className.startsWith("com.lightningstudio.watchrss.phone") }}" + if (error is CloudHttpException) " status=${error.status}" else "")
                if (error is CloudDeviceAuthorizationException) {
                    update(CloudSyncPhase.NEEDS_RECOVERY, "设备身份需要重新验证")
                } else if (_state.value.phase != CloudSyncPhase.NEEDS_RECOVERY) {
                    update(CloudSyncPhase.ERROR, if (error is CloudHttpException && error.status == 413) {
                        "云空间不足，同步尚未完成。请释放云空间后重试，本机内容已保留。"
                    } else error.message ?: "云同步失败")
                }
                throw error
            }
        }

    }

    private suspend fun refreshRssInventory(session: PhoneAccountSession) {
        repository.getCloudEligibleRssSources().forEach { source ->
            if (paused.value) return
            runCatching {
                client.rssInventory(
                    session,
                    source.url,
                    rssInventoryPreferences.mode(source.url)
                )
            }.onSuccess { inventory ->
                repository.mergeCloudRssInventory(inventory)
            }
        }
    }

    private suspend fun pullSnapshot(
        session: PhoneAccountSession,
        accountKey: ByteArray,
        head: CloudSnapshotHead,
        fullTransfer: Boolean
    ): PullOutcome {
        val download = client.snapshot(session, head.id)
        val cachedManifest = cache.loadManifest(session.userId, head.id)
        val encryptedManifest = cachedManifest
            ?: client.download(
                download.manifestSignedUrl,
                head.manifestSizeBytes,
                head.manifestSha256
            ).also {
                cache.storeManifest(
                    session.userId,
                    head.id,
                    it,
                    markAsLocalHead = false
                )
            }
        var downloadedBytes = if (cachedManifest == null) {
            encryptedManifest.size.toLong()
        } else {
            0L
        }
        val manifest = codec.decryptManifest(accountKey, head.id, encryptedManifest)
        cache.recordManifestReferences(
            session.userId,
            head.id,
            manifest.allChunks.map(CloudChunkDescriptor::ciphertextSha256)
        )
        val selectedObjects = manifest.objects.filter { descriptor ->
            descriptor.name == RSS_STATE_OBJECT || descriptor.name == NOTES_STATE_OBJECT || descriptor.name == NOTES_ARCHIVE_OBJECT ||
                (fullTransfer && descriptor.name in setOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT))
        }
        val chunkUrls = download.chunks.associateBy(CloudDownloadObject::sha256)
        selectedObjects.flatMap(CloudObjectDescriptor::chunks)
            .distinctBy(CloudChunkDescriptor::ciphertextSha256)
            .forEach { descriptor ->
                yieldToReading()
                checkApprovalOwner(session)
                if (cache.loadChunk(session.userId, descriptor.ciphertextSha256) == null) {
                    val remote = chunkUrls[descriptor.ciphertextSha256]
                        ?: error("云快照缺少加密块 ${descriptor.ciphertextSha256}")
                    client.download(remote.signedUrl, remote.sizeBytes, remote.sha256).also {
                        cache.storeChunk(session.userId, remote.sha256, it)
                        downloadedBytes += it.size
                    }
                }
            }
        yieldToReading()
        checkApprovalOwner(session)
        update(CloudSyncPhase.MERGING, "正在合并远端快照")
        var changed = false
        var rssPending = 0
        // Restore one logical object at a time: retaining the archive, relay and
        // RSS JSON together can exhaust the heap on a full phone library.
        val restoreOrder = listOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT, RSS_STATE_OBJECT, NOTES_STATE_OBJECT, NOTES_ARCHIVE_OBJECT)
        for (descriptor in selectedObjects.sortedBy { restoreOrder.indexOf(it.name) }) {
            yieldToReading()
            checkApprovalOwner(session)
            traceSync("object-start:${descriptor.name}")
            val bytes = codec.restoreObjects(accountKey, manifest.copy(objects = listOf(descriptor))) {
                sha -> cache.loadChunk(session.userId, sha) ?: error("本地缺少加密块 $sha")
            }.getValue(descriptor.name)
            when (descriptor.name) {
                PRIVATE_LIBRARY_OBJECT -> {
                    val result = backupService.restorePrivateLibrary(bytes, BackupImportMode.MERGE) { checkApprovalOwner(session) }
                    changed = changed || result.changedArticleCount > 0 || result.changedSourceCount > 0 || result.changedSavedItemCount > 0
                }
                RELAY_LIBRARY_OBJECT -> changed = backupService.restoreCloudRelayLibrary(bytes, { checkApprovalOwner(session) }, { yieldToReading() }) > 0 || changed
                RSS_STATE_OBJECT -> {
                    val applied = backupService.applyCloudRssState(bytes, createMissing = true) { checkApprovalOwner(session) }
                    changed = changed || applied.first > 0
                    rssPending = applied.second
                }
                NOTES_STATE_OBJECT -> {
                    NoteCloudStateCodec.decode(bytes).forEach { noteRepository.applyRemote(it, it.modifiedBy) }
                    changed = true
                }
                NOTES_ARCHIVE_OBJECT -> noteTransfer.restoreAssetsZip(bytes)
            }
            traceSync("object-done:${descriptor.name}")
        }
        cache.storeManifest(
            session.userId,
            head.id,
            encryptedManifest,
            markAsLocalHead = false
        )
        return PullOutcome(
            downloadedBytes = downloadedBytes,
            pendingStates = rssPending,
            changed = changed,
            stateApplied = rssPending == 0,
            complete = fullTransfer && rssPending == 0
        )
    }

    private suspend fun downloadSnapshotObjects(
        session: PhoneAccountSession,
        snapshotId: String
    ): DecodedSnapshotObjects {
        val download = client.snapshot(session, snapshotId)
        val cachedManifest = cache.loadManifest(session.userId, snapshotId)
        val encryptedManifest = cachedManifest
            ?: client.download(
                download.manifestSignedUrl,
                download.head.manifestSizeBytes,
                download.head.manifestSha256
            ).also {
                cache.storeManifest(
                    session.userId,
                    snapshotId,
                    it,
                    markAsLocalHead = false
                )
            }
        val accountKey = keyManager.getAccountKey(session.userId, download.head.keyVersion)
            ?: error("缺少第${download.head.keyVersion}版账号密钥")
        val manifest = codec.decryptManifest(accountKey, snapshotId, encryptedManifest)
        cache.recordManifestReferences(
            session.userId,
            snapshotId,
            manifest.allChunks.map(CloudChunkDescriptor::ciphertextSha256)
        )
        val remoteChunks = download.chunks.associateBy(CloudDownloadObject::sha256)
        manifest.allChunks.forEach { descriptor ->
            if (cache.loadChunk(session.userId, descriptor.ciphertextSha256) == null) {
                val remote = remoteChunks[descriptor.ciphertextSha256]
                    ?: error("云快照缺少加密块 ${descriptor.ciphertextSha256}")
                client.download(remote.signedUrl, remote.sizeBytes, remote.sha256).also {
                    cache.storeChunk(session.userId, remote.sha256, it)
                }
            }
        }
        val objects = codec.restoreObjects(accountKey, manifest) { sha ->
            cache.loadChunk(session.userId, sha) ?: error("本地缺少加密块 $sha")
        }
        return DecodedSnapshotObjects(download, objects)
    }

    private suspend fun uploadSnapshot(
        session: PhoneAccountSession,
        accountKey: ByteArray,
        bootstrap: CloudBootstrap,
        heads: List<CloudSnapshotHead>,
        fullTransfer: Boolean,
        keyVersion: Int
    ): UploadOutcome {
        val previous = loadLatestManifest(session.userId, accountKey, keyVersion)
        val privateFingerprint = if (fullTransfer) backupService.privateCloudFingerprint() else null
        val pendingManifest = cache.loadPendingManifest(session.userId)?.let { (id, bytes) ->
            runCatching { codec.decryptManifest(accountKey, id, bytes) }.getOrNull()
                ?.takeIf { it.keyVersion == keyVersion }
        }
        // A retry can carry an authenticated pending body when every block is still local.
        val bodySource = listOfNotNull(previous, pendingManifest).firstOrNull { candidate ->
            candidate.settings.optString("privateBodyFingerprintV1") == privateFingerprint &&
                ((candidate === previous && heads.any { it.id == candidate.snapshotId }) || candidate.allChunks.all { cache.loadChunk(session.userId, it.ciphertextSha256) != null })
        }
        val priorBodies = bodySource?.objects.orEmpty().filter { it.name == PRIVATE_LIBRARY_OBJECT || it.name == RELAY_LIBRARY_OBJECT }
        val carryPrivate = fullTransfer && privateFingerprint != null &&
            bodySource?.settings?.optString("privateBodyFingerprintV1") == privateFingerprint &&
            priorBodies.map { it.name }.toSet() == setOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT) &&
            priorBodies.all { it.readerKeyEnvelope == null }
        val logicalObjects: List<CloudLogicalObject>
        val carried: List<CloudObjectDescriptor>
        if (carryPrivate) {
            traceSync("upload-private:reuse")
            logicalObjects = listOfNotNull(
                CloudLogicalObject(RSS_STATE_OBJECT, backupService.createCloudRssState()),
                CloudLogicalObject(NOTES_STATE_OBJECT, NoteCloudStateCodec.encode(noteRepository.allNotes())),
                runCatching { CloudLogicalObject(NOTES_ARCHIVE_OBJECT, noteTransfer.exportZip()) }.getOrNull()
            )
            carried = priorBodies
        } else if (fullTransfer) {
            traceSync("upload-private:build")
            logicalObjects = listOfNotNull(
                CloudLogicalObject(PRIVATE_LIBRARY_OBJECT, backupService.createCloudPrivateArchiveFile(), false),
                CloudLogicalObject(RSS_STATE_OBJECT, backupService.createCloudRssState()),
                CloudLogicalObject(NOTES_STATE_OBJECT, NoteCloudStateCodec.encode(noteRepository.allNotes())),
                runCatching { CloudLogicalObject(NOTES_ARCHIVE_OBJECT, noteTransfer.exportZip()) }.getOrNull(),
                CloudLogicalObject(RELAY_LIBRARY_OBJECT, backupService.createCloudRelayLibrary())
            )
            carried = emptyList()
        } else {
            val priorPrivate = previous?.objects?.firstOrNull { it.name == PRIVATE_LIBRARY_OBJECT }
                ?: return UploadOutcome(false, 0)
            val priorRelay = previous.objects.firstOrNull { it.name == RELAY_LIBRARY_OBJECT }
            logicalObjects = listOfNotNull(
                CloudLogicalObject(RSS_STATE_OBJECT, backupService.createCloudRssState()),
                CloudLogicalObject(NOTES_STATE_OBJECT, NoteCloudStateCodec.encode(noteRepository.allNotes())),
                runCatching { CloudLogicalObject(NOTES_ARCHIVE_OBJECT, noteTransfer.exportZip()) }.getOrNull()
            )
            carried = listOfNotNull(priorPrivate, priorRelay)
        }
        val parentHeads = heads.associate { it.sourceDeviceId to it.id }
        val observedHeads = heads.associate { it.sourceDeviceId to it.deviceSequence }
        val contentHash = snapshotContentHash(logicalObjects, carried, keyVersion, privateFingerprint)
        val contentChanged = settings.lastContentHash(fullTransfer) != contentHash || heads.none { it.sourceDeviceId == deviceId }
        val mergeNeeded = heads.isNotEmpty() && heads.none { candidate ->
            heads.all { current ->
                current.sourceDeviceId == candidate.sourceDeviceId ||
                    current.deviceSequence <=
                    (candidate.observedHeads[current.sourceDeviceId] ?: 0L)
            }
        }
        if (!contentChanged && !mergeNeeded) {
            return UploadOutcome(false, 0)
        }
        val serverSequence = bootstrap.devices
            .firstOrNull { it.deviceId == deviceId }
            ?.lastSequence
            ?: 0L
        val reusableManifests = heads.filter { it.keyVersion == keyVersion }.mapNotNull { head ->
            cache.loadManifest(session.userId, head.id)?.let { bytes ->
                runCatching { codec.decryptManifest(accountKey, head.id, bytes) }.getOrNull()
            }
        }
        val snapshotSettings = if (!fullTransfer) org.json.JSONObject(previous?.settings?.toString() ?: "{}") else org.json.JSONObject()
        if (privateFingerprint != null && privateFingerprint == backupService.privateCloudFingerprint()) {
            snapshotSettings.put("privateBodyFingerprintV1", privateFingerprint)
        }
        val encrypted = codec.create(
            accountKey = accountKey,
            keyVersion = keyVersion,
            sourceDeviceId = deviceId,
            deviceSequence = settings.nextSequence(serverSequence),
            logicalObjects = logicalObjects.map { it.copy(incrementalChunks = true) },
            parentHeads = parentHeads,
            observedHeads = observedHeads,
            previousManifest = previous,
            carryForwardObjects = carried,
            reusableManifests = reusableManifests + listOfNotNull(pendingManifest),
            snapshotSettings = snapshotSettings,
            onReusedChunk = { descriptor, materialize ->
                if (cache.loadChunk(session.userId, descriptor.ciphertextSha256) == null) {
                    cache.storeChunk(session.userId, descriptor.ciphertextSha256, materialize())
                }
            }
        )
        encrypted.newCiphertextChunks.forEach { (sha, bytes) ->
            cache.storeChunk(session.userId, sha, bytes)
        }
        checkApprovalOwner(session)
        cache.storePendingManifest(session.userId, encrypted.manifest.snapshotId, encrypted.encryptedManifest,
            encrypted.manifest.allChunks.map { it.ciphertextSha256 })
        traceSync("upload-chunks:new=${encrypted.newCiphertextChunks.size}:total=${encrypted.manifest.allChunks.size}:newBytes=${encrypted.newCiphertextChunks.values.sumOf { it.size.toLong() }}")
        update(CloudSyncPhase.UPLOADING, "正在预留云空间", bootstrap.member)
        val reservation = client.reserveSnapshot(session, encrypted, settings.retentionDays)
        traceSync("upload-plan:objects=${reservation.missingObjects.size}:bytes=${reservation.missingObjects.sumOf { it.sizeBytes }}")
        var uploadedBytes = 0L
        var uploadedObjects = 0
        val uploadSlots = kotlinx.coroutines.sync.Semaphore(3)
        val progressLock = Any()
        kotlinx.coroutines.coroutineScope {
        reservation.missingObjects.map { target -> async { uploadSlots.withPermit {
            checkApprovalOwner(session)
            val bytes = if (target.kind == "manifest") {
                encrypted.encryptedManifest
            } else {
                cache.loadChunk(session.userId, target.sha256)
                    ?: error("本地缺少待上传加密块 ${target.sha256}")
            }
            var attempt = 0
            var sent = false
            while (true) {
                checkApprovalOwner(session)
                try {
                    // Late blocks need a fresh URL on slow links.
                    val fresh = if (bytes.size <= 2 * 1024 * 1024) client.refreshUpload(session, target) else target
                    if (!fresh.alreadyUploaded) {
                        uploader.upload(fresh, bytes)
                        sent = true
                    }
                    break
                } catch (error: java.io.IOException) {
                    val status = when (error) {
                        is CloudHttpException -> error.status
                        is CloudObjectUploadException -> error.status
                        else -> null
                    }
                    if (status != null && status !in setOf(408, 429, 500, 502, 503, 504)) throw error
                    if (++attempt >= 3) throw error
                    traceSync("upload-retry:$attempt")
                    kotlinx.coroutines.delay(attempt * 1000L)
                }
            }
            synchronized(progressLock) {
                if (sent) uploadedBytes += bytes.size
                uploadedObjects++
                if (uploadedObjects % 20 == 0 || uploadedObjects == reservation.missingObjects.size) traceSync("upload-progress:$uploadedObjects/${reservation.missingObjects.size}:bytes=$uploadedBytes")
                update(CloudSyncPhase.UPLOADING, "已上传 ${formatBytes(uploadedBytes)}", bootstrap.member, uploadedBytes)
            }
        } } }.awaitAll()
        }
        traceSync("upload-verify:start")
        update(CloudSyncPhase.UPLOADING, "正在完成同步", bootstrap.member, uploadedBytes)
        client.completeSnapshot(session, encrypted)
        traceSync("upload-verify:done")
        cache.storeManifest(
            session.userId,
            encrypted.manifest.snapshotId,
            encrypted.encryptedManifest,
            markAsLocalHead = true
        )
        cache.recordManifestReferences(
            session.userId,
            encrypted.manifest.snapshotId,
            encrypted.manifest.allChunks.map(CloudChunkDescriptor::ciphertextSha256)
        )
        cache.clearPendingManifest(session.userId, encrypted.manifest.snapshotId)
        cache.prune(session.userId)
        settings.markApplied(deviceId, encrypted.manifest.deviceSequence, full = fullTransfer)
        settings.markApplied(deviceId, encrypted.manifest.deviceSequence, full = false)
        settings.markUploaded(contentHash, parentHeads, full = fullTransfer)
        if (fullTransfer) {
            val stateObjects = logicalObjects.filter { it.name == RSS_STATE_OBJECT || it.name == NOTES_STATE_OBJECT || it.name == NOTES_ARCHIVE_OBJECT }
            val bodyObjects = encrypted.manifest.objects.filter {
                it.name == PRIVATE_LIBRARY_OBJECT || it.name == RELAY_LIBRARY_OBJECT
            }
            settings.markUploaded(
                snapshotContentHash(stateObjects, bodyObjects, keyVersion),
                parentHeads,
                full = false
            )
        }
        return UploadOutcome(true, uploadedBytes)
    }

    private fun loadLatestManifest(
        userId: String,
        accountKey: ByteArray,
        keyVersion: Int
    ): CloudSnapshotManifest? =
        cache.loadLatestManifest(userId)?.let { (snapshotId, bytes) ->
            runCatching { codec.decryptManifest(accountKey, snapshotId, bytes) }
                .getOrNull()
                ?.takeIf { it.keyVersion == keyVersion }
        }

    // Keep the legacy installation's ECDH key and account keys available while
    // moving cloud identity to the ID already bound to the app authorization.
    private fun restoreLegacyDeviceEnvelopes(session: PhoneAccountSession, bootstrap: CloudBootstrap) {
        val legacy = legacyDeviceId?.takeIf { it != deviceId } ?: return
        if (keyManager.hasAccountKey(session.userId)) return
        val device = bootstrap.devices.firstOrNull { it.deviceId == legacy && it.revokedAt == null }
            ?: return
        val envelopes = deviceApprovalEnvelopes(bootstrap, legacy)
        if (envelopes.isEmpty()) return
        if (device.publicKeySpki != keyManager.devicePublicKeySpki(session.userId, legacy)) return
        envelopes.forEach { keyManager.unwrapDeviceEnvelope(session.userId, legacy, it.envelope) }
    }

    private val identityMigrationMutex = Mutex()

    private suspend fun ensureApprovalIdentity(session: PhoneAccountSession, bootstrap: CloudBootstrap) {
        if (!bootstrap.member.writable || !keyManager.hasAccountKey(session.userId)) return
        identityMigrationMutex.withLock {
            checkApprovalOwner(session)
            val device = bootstrap.devices.firstOrNull { it.deviceId == deviceId }
            // An explicit server revocation must never be undone by migration.
            if (device?.revokedAt != null) return@withLock
            val versions = deviceApprovalEnvelopes(bootstrap, deviceId).map { it.envelope.keyVersion }
            if (device != null && device.publicKeySpki == keyManager.devicePublicKeySpki(session.userId, deviceId)
                && keyManager.keyVersions(session.userId).all { it in versions }) return@withLock
            registerCurrentDevice(session)
            checkApprovalOwner(session)
            storeEnvelopeForCurrentDevice(session)
            checkApprovalOwner(session)
        }
    }

    private suspend fun registerCurrentDevice(session: PhoneAccountSession) {
        client.registerDevice(
            session = session,
            deviceId = deviceId,
            displayName = "手机 · ${Build.MODEL}",
            publicKeySpki = keyManager.devicePublicKeySpki(session.userId, deviceId),
            keyVersion = keyManager.currentKeyVersion(session.userId)
        )
    }

    private suspend fun storeEnvelopeForCurrentDevice(session: PhoneAccountSession) {
        val publicKey = CloudKeyEnvelopeCodec.decodeP256PublicKey(
            keyManager.devicePublicKeySpki(session.userId, deviceId)
        )
        keyManager.keyVersions(session.userId).forEach { version ->
            val accountKey = keyManager.getAccountKey(session.userId, version)
                ?: error("当前手机缺少第${version}版账号密钥")
            client.storeDeviceEnvelope(
                session = session,
                recipientDeviceId = deviceId,
                createdByDeviceId = deviceId,
                envelope = CloudKeyEnvelopeCodec.createDeviceEnvelope(
                    accountKey,
                    session.userId,
                    deviceId,
                    publicKey,
                    keyVersion = version
                )
            )
        }
    }

    private fun requireSession(): PhoneAccountSession =
        accountRepository.session.value ?: error("请先登录腕上RSS账号")

    private fun snapshotContentHash(
        objects: List<CloudLogicalObject>,
        carried: List<CloudObjectDescriptor>,
        keyVersion: Int,
        privateFingerprint: String? = null
    ): String {
        val bodyNames = setOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT)
        val description = buildString {
            append("keyVersion:").append(keyVersion).append(';')
            if (privateFingerprint != null) append("private:").append(privateFingerprint).append(';')
            objects.filter { privateFingerprint == null || it.name !in bodyNames }.sortedBy(CloudLogicalObject::name).forEach {
                append(it.name).append(':').append(it.sha256()).append(';')
            }
            carried.filter { privateFingerprint == null || it.name !in bodyNames }.sortedBy(CloudObjectDescriptor::name).forEach { descriptor ->
                append(descriptor.name).append(':')
                descriptor.chunks.forEach { append(it.ciphertextSha256) }
                append(';')
            }
        }
        return CloudSnapshotCodec.sha256(description.toByteArray())
    }

    private fun update(
        phase: CloudSyncPhase,
        message: String,
        member: CloudMemberState? = _state.value.member,
        uploadedBytes: Long = _state.value.uploadedBytes,
        downloadedBytes: Long = _state.value.downloadedBytes,
        completedAt: Long = _state.value.lastCompletedAt
    ) {
        _state.value = PhoneCloudSyncState(
            phase = phase,
            message = message,
            member = member,
            uploadedBytes = uploadedBytes,
            downloadedBytes = downloadedBytes,
            lastCompletedAt = completedAt
        )
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KiB" else "%.1f MiB".format(bytes / 1048576.0)

    private data class PullOutcome(
        val downloadedBytes: Long,
        val pendingStates: Int,
        val changed: Boolean,
        val stateApplied: Boolean,
        val complete: Boolean
    )

    private data class UploadOutcome(val uploaded: Boolean, val bytes: Long)

    private data class DecodedSnapshotObjects(
        val download: CloudSnapshotDownload,
        val objects: Map<String, ByteArray>
    )

    private companion object {
        private const val PRIVATE_LIBRARY_OBJECT = "private-library.wrss"
        private const val RSS_STATE_OBJECT = "rss-state.json"
        private const val NOTES_STATE_OBJECT = "notes-state.json"
        private const val NOTES_ARCHIVE_OBJECT = "notes-assets.zip"
        private const val RELAY_LIBRARY_OBJECT = "library-sync.json"
    }
}

internal fun deviceApprovalKeyVersion(bootstrap: CloudBootstrap): Int =
    bootstrap.keyEnvelopes.maxOfOrNull { it.envelope.keyVersion }
        ?: bootstrap.devices.maxOfOrNull { it.keyVersion }
        ?: 1

internal fun deviceApprovalEnvelopes(
    bootstrap: CloudBootstrap,
    deviceId: String
): List<StoredCloudKeyEnvelope> = bootstrap.keyEnvelopes
    .filter { it.recipientType == "device" && it.recipientDeviceId == deviceId }
    .sortedBy { it.envelope.keyVersion }

internal fun isCloudKeySetupComplete(
    envelopes: List<StoredCloudKeyEnvelope>,
    devices: List<RegisteredCloudDevice>,
    deviceId: String,
    keyVersion: Int
): Boolean {
    val recoveryReady = envelopes.any {
        it.recipientType == "recovery" && it.envelope.keyVersion == keyVersion
    }
    val deviceEnvelopeReady = envelopes.any {
        it.recipientType == "device" &&
            it.recipientDeviceId == deviceId &&
            it.envelope.keyVersion == keyVersion
    }
    val deviceReady = devices.any {
        it.deviceId == deviceId && it.revokedAt == null && it.keyVersion == keyVersion
    }
    return recoveryReady && deviceEnvelopeReady && deviceReady
}
