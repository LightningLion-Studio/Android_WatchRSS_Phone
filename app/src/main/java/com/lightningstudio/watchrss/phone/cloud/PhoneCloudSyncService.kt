package com.lightningstudio.watchrss.phone.cloud

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

enum class CloudSyncPhase {
    IDLE,
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
    private val codec: CloudSnapshotCodec = CloudSnapshotCodec()
) {
    private val noteTransfer = NoteImportExportService(context, noteRepository)
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(PhoneCloudSyncState())
    val state: StateFlow<PhoneCloudSyncState> = _state

    suspend fun loadMembership(): CloudMemberState =
        client.membership(requireSession()).also { member ->
            update(CloudSyncPhase.IDLE, "会员状态已更新", member)
        }

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
        return client.bootstrap(session).also { bootstrap ->
            update(CloudSyncPhase.IDLE, "云服务已连接", bootstrap.member)
        }
    }

    suspend fun listSnapshotHeads(): List<CloudSnapshotHead> =
        client.snapshotHeads(requireSession())

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
        update(CloudSyncPhase.MERGING, "正在恢复云快照")
        val privateResult = privateArchive?.let {
            backupService.restorePrivateLibrary(it, mode)
        }
        val relayChanged = decoded.objects[RELAY_LIBRARY_OBJECT]
            ?.let { backupService.restoreCloudRelayLibrary(it) }
            ?: 0
        val stateResult = decoded.objects[RSS_STATE_OBJECT]
            ?.let { backupService.applyCloudRssState(it) }
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
        client.deleteSnapshot(requireSession(), snapshotId)
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

    suspend fun syncNow(manual: Boolean = false): PhoneCloudSyncResult =
        syncMutex.withLock {
            var uploadedBytes = 0L
            var downloadedBytes = 0L
            var applied = 0
            var pendingStates = 0
            var uploaded = false
            try {
                update(CloudSyncPhase.CHECKING, "正在检查云端")
                require(networkGate.isConnected()) { "当前没有可用网络" }
                val session = requireSession()
                var bootstrap = client.bootstrap(session)
                if (!keyManager.hasAccountKey(session.userId)) {
                    update(
                        CloudSyncPhase.NEEDS_RECOVERY,
                        "请输入24词恢复密钥，或由已授权设备批准",
                        bootstrap.member
                    )
                    error("当前设备尚未获得云端解密密钥")
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
                    return@withLock PhoneCloudSyncResult(0, 0, 0, false, 0)
                }
                val heads = client.snapshotHeads(session)
                for (head in heads.sortedBy(CloudSnapshotHead::deviceSequence)) {
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

                if (bootstrap.member.writable) {
                    val currentKeyVersion = keyManager.currentKeyVersion(session.userId)
                    val accountKey = keyManager.getAccountKey(session.userId, currentKeyVersion)
                        ?: error("当前账号主密钥不存在")
                    val freshHeads = client.snapshotHeads(session)
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
                if (!fullTransfer && !uploaded) {
                    update(
                        CloudSyncPhase.WAITING_FOR_WIFI,
                        "小状态已同步；正文等待Wi‑Fi",
                        bootstrap.member,
                        uploadedBytes,
                        downloadedBytes
                    )
                } else {
                    update(
                        CloudSyncPhase.COMPLETE,
                        "云同步完成",
                        bootstrap.member,
                        uploadedBytes,
                        downloadedBytes,
                        System.currentTimeMillis()
                    )
                }
                PhoneCloudSyncResult(
                    uploadedBytes = uploadedBytes,
                    downloadedBytes = downloadedBytes,
                    snapshotsApplied = applied,
                    snapshotUploaded = uploaded,
                    pendingArticleStates = pendingStates
                )
            } catch (error: Exception) {
                if (_state.value.phase != CloudSyncPhase.NEEDS_RECOVERY) {
                    update(CloudSyncPhase.ERROR, error.message ?: "云同步失败")
                }
                throw error
            }
        }

    private suspend fun refreshRssInventory(session: PhoneAccountSession) {
        repository.getCloudEligibleRssSources().forEach { source ->
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
        val selectedObjects = manifest.objects.filter { descriptor ->
            fullTransfer || descriptor.name == RSS_STATE_OBJECT || descriptor.name == NOTES_STATE_OBJECT || descriptor.name == NOTES_ARCHIVE_OBJECT
        }
        val chunkUrls = download.chunks.associateBy(CloudDownloadObject::sha256)
        selectedObjects.flatMap(CloudObjectDescriptor::chunks)
            .distinctBy(CloudChunkDescriptor::ciphertextSha256)
            .forEach { descriptor ->
                if (cache.loadChunk(session.userId, descriptor.ciphertextSha256) == null) {
                    val remote = chunkUrls[descriptor.ciphertextSha256]
                        ?: error("云快照缺少加密块 ${descriptor.ciphertextSha256}")
                    client.download(remote.signedUrl, remote.sizeBytes, remote.sha256).also {
                        cache.storeChunk(session.userId, remote.sha256, it)
                        downloadedBytes += it.size
                    }
                }
            }
        update(CloudSyncPhase.MERGING, "正在合并远端快照")
        val objects = codec.restoreObjects(
            accountKey,
            manifest.copy(objects = selectedObjects)
        ) { sha -> cache.loadChunk(session.userId, sha) ?: error("本地缺少加密块 $sha") }
        var changed = false
        if (fullTransfer) {
            objects[PRIVATE_LIBRARY_OBJECT]?.let { archive ->
                val result = backupService.restorePrivateLibrary(archive, BackupImportMode.MERGE)
                changed = changed ||
                    result.changedArticleCount > 0 ||
                    result.changedSourceCount > 0 ||
                    result.changedSavedItemCount > 0
            }
            objects[RELAY_LIBRARY_OBJECT]?.let { relay ->
                changed = backupService.restoreCloudRelayLibrary(relay) > 0 || changed
            }
        }
        val (rssApplied, rssPending) = objects[RSS_STATE_OBJECT]
            ?.let { backupService.applyCloudRssState(it) }
            ?: (0 to 0)
        changed = changed || rssApplied > 0
        objects[NOTES_STATE_OBJECT]?.let { bytes ->
            NoteCloudStateCodec.decode(bytes).forEach { noteRepository.applyRemote(it, it.modifiedBy) }
            changed = true
        }
        objects[NOTES_ARCHIVE_OBJECT]?.let(noteTransfer::restoreAssetsZip)
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
        val logicalObjects: List<CloudLogicalObject>
        val carried: List<CloudObjectDescriptor>
        if (fullTransfer) {
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
        val contentHash = snapshotContentHash(logicalObjects, carried, keyVersion)
        val contentChanged = settings.lastContentHash(fullTransfer) != contentHash
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
        val encrypted = codec.create(
            accountKey = accountKey,
            keyVersion = keyVersion,
            sourceDeviceId = deviceId,
            deviceSequence = settings.nextSequence(serverSequence),
            logicalObjects = logicalObjects,
            parentHeads = parentHeads,
            observedHeads = observedHeads,
            previousManifest = previous,
            carryForwardObjects = carried
        )
        encrypted.newCiphertextChunks.forEach { (sha, bytes) ->
            cache.storeChunk(session.userId, sha, bytes)
        }
        update(CloudSyncPhase.UPLOADING, "正在预留云空间", bootstrap.member)
        val reservation = client.reserveSnapshot(session, encrypted, settings.retentionDays)
        var uploadedBytes = 0L
        reservation.missingObjects.forEach { target ->
            val bytes = if (target.kind == "manifest") {
                encrypted.encryptedManifest
            } else {
                cache.loadChunk(session.userId, target.sha256)
                    ?: error("本地缺少待上传加密块 ${target.sha256}")
            }
            uploader.upload(target, bytes)
            uploadedBytes += bytes.size
            update(
                CloudSyncPhase.UPLOADING,
                "已上传 ${formatBytes(uploadedBytes)}",
                bootstrap.member,
                uploadedBytes
            )
        }
        client.completeSnapshot(session, encrypted)
        cache.storeManifest(
            session.userId,
            encrypted.manifest.snapshotId,
            encrypted.encryptedManifest,
            markAsLocalHead = true
        )
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
        keyVersion: Int
    ): String {
        val description = buildString {
            append("keyVersion:").append(keyVersion).append(';')
            objects.sortedBy(CloudLogicalObject::name).forEach {
                append(it.name).append(':').append(it.sha256()).append(';')
            }
            carried.sortedBy(CloudObjectDescriptor::name).forEach { descriptor ->
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
