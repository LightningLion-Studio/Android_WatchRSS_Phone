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
import org.json.JSONObject
import org.json.JSONArray

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
        keyManager::currentKeyVersion, keyManager::getAccountKey, ::checkApprovalOwner,
        anchorVersion = { trustedBootstrap?.keyCommitments?.minOfOrNull { it.keyVersion } ?: error("缺少锚定密钥") },
        refreshKeys = { session -> validatedBootstrap(session) })
    private val noteTransfer = NoteImportExportService(context, noteRepository)
    private val syncMutex = CloudSyncSingleFlight()
    private val _state = MutableStateFlow(PhoneCloudSyncState())
    val state: StateFlow<PhoneCloudSyncState> = _state

    private var trustedBootstrap: CloudBootstrap? = null
    private val approvalMutex = Mutex()
    private var pendingSetup: RecoveryKeySetup? = null
    private var pendingSetupOwner: String? = null
    private var pendingSetupEpoch: String? = null
    private var pendingRotation: RecoveryKeySetup? = null
    private var pendingRotationOwner: String? = null
    private var pendingRotationEpoch: String? = null
    private val approvalsDelegate = lazy { CloudApprovalV2(client, deviceId, ::requireSession,
        { keyManager.devicePublicKeySpki(it.userId, deviceId) },
        { session -> val bootstrap = validatedBootstrap(session); registerCurrentDevice(session, bootstrap.libraryEpoch) },
        ::approvalPayload, ::acceptApprovalPayload) }
    val approvals: CloudApprovalV2 get() = approvalsDelegate.value
    private val _needsReapproval = MutableStateFlow<List<RegisteredCloudDevice>>(emptyList())
    val needsReapproval: StateFlow<List<RegisteredCloudDevice>> = _needsReapproval
    private val _keyWarning = MutableStateFlow<String?>(null)
    val keyWarning: StateFlow<String?> = _keyWarning
    private val _deviceRegistrationConflict = MutableStateFlow(false)
    val deviceRegistrationConflict: StateFlow<Boolean> = _deviceRegistrationConflict
    private val _recordError = MutableStateFlow<String?>(null)
    val recordError: StateFlow<String?> = _recordError

    init {
        client.checkSession = ::checkApprovalOwner
        client.currentDeviceId = deviceId
        keyManager.bindBackend(client.backendBaseUrl)
        accountRepository.session.value?.let { bindScope(it); settings.claimLegacyOwner(it.userId) }
        accountRepository.onReleaseCloudAuthorization = ::releaseCloudAuthorization
        accountRepository.onClearCloudState = ::discardSessionCloudState
        client.onDeviceKeyConflict = { session -> checkApprovalOwner(session); _deviceRegistrationConflict.value = true }
        client.onEpochMismatch = { session -> invalidateEpoch(session) }
        client.onBootstrap = { session, bootstrap -> observeBootstrap(session, bootstrap) }
        client.onRecordMetadata = { session, json ->
            if (json.has("libraryEpoch")) {
                val current = trustedBootstrap ?: error("请先读取云资料库")
                observeBootstrap(session, current.copy(libraryEpoch = json.getString("libraryEpoch"),
                    keyVersionFloor = json.getInt("keyVersionFloor"), keyCommitments = client.parseCommitments(json)))
            }
        }
    }
    internal suspend fun releaseCloudAuthorization(session: PhoneAccountSession) = releaseCloudDevice(
        revokeRemote = {
            val bootstrap = runCatching { client.bootstrap(session) }.getOrNull()
            val expectedEpoch = bootstrap?.libraryEpoch ?: client.knownEpoch(session)
            if (expectedEpoch != null) {
                runCatching { client.revokeDevice(session, deviceId, expectedEpoch) }
                legacyDeviceId?.takeIf { it != deviceId }?.let { legacy ->
                    val remote = bootstrap?.devices?.firstOrNull { it.deviceId == legacy }
                    if (remote != null && runCatching { remote.publicKeySpki.base64Bytes().contentEquals(keyManager.devicePublicKeySpki(session.userId, legacy).base64Bytes()) }.getOrDefault(false))
                        runCatching { client.revokeDevice(session, legacy, expectedEpoch) }
                }
            }
        }, clearDevicePrivateKey = {
            keyManager.clearDeviceKey(session.userId, deviceId)
            legacyDeviceId?.takeIf { it != deviceId }?.let { keyManager.clearDeviceKey(session.userId, it) }
        })
    internal fun discardSessionCloudState(session: PhoneAccountSession) = clearCloudScope(session)
    private fun bindScope(session: PhoneAccountSession) {
        checkApprovalOwner(session); settings.bindScope(client.backendBaseUrl, session.userId)
    }
    private fun clearCloudScope(session: PhoneAccountSession) {
        settings.bindScope(client.backendBaseUrl, session.userId)
        settings.claimLegacyOwner(session.userId)
        keyManager.clearAccountKeys(session.userId); settings.clearScope()
        recordStore.clear(client.scopeId(session)); cache.clear(client.scopeId(session))
        recordStore.clear(session.userId); cache.clear(session.userId)
        uploader.clearScope(client.scopeId(session))
        synchronized(readingManifests) { readingManifests.clear() }
        pendingSetup?.pendingKey?.fill(0); pendingSetup = null; pendingRotation?.pendingKey?.fill(0); pendingRotation = null
        trustedBootstrap = null
        _deviceRegistrationConflict.value = false
        _keyWarning.value = null; _recordError.value = null; _needsReapproval.value = emptyList()
        client.forgetEpoch(session)
        pendingSetupOwner = null; pendingSetupEpoch = null
        if (approvalsDelegate.isInitialized()) approvals.clear()
    }
    private fun invalidateEpoch(session: PhoneAccountSession) {
        clearCloudScope(session)
        update(CloudSyncPhase.NEEDS_RECOVERY, "云资料库已重置或密钥失效，请恢复或重新批准")
    }
    private fun localKeys(session: PhoneAccountSession): Map<Int, ByteArray> = keyManager.keyVersions(session.userId)
        .associateWith { requireNotNull(keyManager.getAccountKey(session.userId, it)) }
    private fun verifyKeys(session: PhoneAccountSession, bootstrap: CloudBootstrap, keys: Map<Int, ByteArray>) =
        CloudKeyTrust.validate(session.userId, bootstrap.libraryEpoch, bootstrap.keyVersionFloor, bootstrap.keyCommitments, keys)
    private fun verifyLocalKeys(session: PhoneAccountSession, bootstrap: CloudBootstrap) {
        val keys = localKeys(session)
        try { verifyKeys(session, bootstrap, keys) } finally { keys.values.forEach { it.fill(0) } }
    }
    private fun observeBootstrap(session: PhoneAccountSession, bootstrap: CloudBootstrap) {
        bindScope(session)
        require(bootstrap.libraryEpoch.isNotBlank()) { "云服务需要升级到同步协议 v2" }
        val changed = settings.observeEpoch(bootstrap.libraryEpoch) { invalidateEpoch(session) }
        client.useEpoch(session, bootstrap.libraryEpoch)
        if (changed) throw CloudTrustException()
        val keys = localKeys(session)
        val remoteDevice = bootstrap.devices.firstOrNull { it.deviceId == deviceId }
        clearRevokedCloudDeviceIdentity(bootstrap, deviceId) { keyManager.clearDeviceKey(session.userId, deviceId) }
        if (keys.isNotEmpty() && remoteDevice?.revokedAt != null) {
            keys.values.forEach { it.fill(0) }; invalidateEpoch(session); throw CloudTrustException()
        }
        try {
            if (keys.isNotEmpty() && bootstrap.keyCommitments.isNotEmpty()) {
                val verified = CloudKeyTrust.verifiedSubset(session.userId, bootstrap.libraryEpoch,
                    bootstrap.keyVersionFloor, bootstrap.keyCommitments, keys)
                val rejected = keys.keys - verified.keys
                if (rejected.isNotEmpty()) {
                    // Same-epoch failure must not destroy other verified versions or device identity.
                    if (verified.isEmpty()) {
                        clearCloudScope(session)
                        settings.observeEpoch(bootstrap.libraryEpoch) {}
                        client.useEpoch(session, bootstrap.libraryEpoch)
                    } else keyManager.replaceVerifiedKeys(session.userId, verified)
                    rejected.forEach { settings.quarantineKey(it, "local_key_validation_failed") }
                    _keyWarning.value = "部分密钥版本验证失败，已隔离；请恢复或重新批准"
                    if (verified.isEmpty()) { update(CloudSyncPhase.NEEDS_RECOVERY, _keyWarning.value!!); throw CloudTrustException() }
                }
            }
        } finally { keys.values.forEach { it.fill(0) } }
        trustedBootstrap = bootstrap
        _needsReapproval.value = if (keyManager.hasAccountKey(session.userId)) bootstrap.devices.filter { it.revokedAt == null && !it.deviceId.startsWith("reader-migration-") && !deviceAuthenticated(session, bootstrap, it) } else emptyList()
    }
    private suspend fun validatedBootstrap(session: PhoneAccountSession): CloudBootstrap {
        var bootstrap = client.bootstrap(session)
        val operationEpoch = bootstrap.libraryEpoch
        // The first v2 writer claims every legacy key, in order. Conflicts are re-read and verified.
        val keys = localKeys(session)
        try {
            if (keys.isNotEmpty() && bootstrap.keyCommitments.isEmpty()) {
                if (!legacyMigrationHasCompleteKeySet(bootstrap, keys.keys)) {
                    update(CloudSyncPhase.NEEDS_RECOVERY, "请等待持有全部密钥的设备迁移，或使用24词恢复")
                    throw CloudLegacyMigrationPendingException()
                }
                // Legacy migration needs an existing server-authorized envelope. A reset
                // removes it, so an unscoped pre-v2 key cannot claim the new empty epoch.
                val ownerDevice = listOfNotNull(deviceId, legacyDeviceId).distinct().firstOrNull { id ->
                    legacyMigrationAuthorized(bootstrap, id, keyManager.devicePublicKeySpki(session.userId, id), keys) {
                        keyManager.unwrapDeviceEnvelope(session.userId, id, it)
                    }
                }
                if (ownerDevice == null) throw CloudLegacyMigrationPendingException()
                if (bootstrap.devices.none { it.deviceId == deviceId && it.revokedAt == null }) {
                    client.registerDevice(session, deviceId, "手机 · ${Build.MODEL}", keyManager.devicePublicKeySpki(session.userId, deviceId), keys.keys.max(), expectedEpoch = bootstrap.libraryEpoch)
                }
                val rows = keys.toSortedMap().map { (version, key) ->
                    val commitment = CloudProtocolV2.commitment(key, session.userId, bootstrap.libraryEpoch, version)
                    val proof = if (version == keys.keys.min()) null else CloudProtocolV2.rotationProof(
                        requireNotNull(keys[version - 1]), session.userId, bootstrap.libraryEpoch, version, commitment)
                    CloudKeyCommitment(version, commitment, proof)
                }
                storeLegacyCommitments(client, session, deviceId, rows, bootstrap.libraryEpoch)
                bootstrap = client.bootstrap(session)
                checkCloudEpoch(session, operationEpoch)
            }
            if (keys.isNotEmpty()) {
                verifyKeys(session, bootstrap, keys)
                val candidates = keys.toMutableMap()
                try {
                    deviceApprovalEnvelopes(bootstrap, deviceId).filter { it.envelope.keyVersion !in candidates }.forEach { stored ->
                        val version = stored.envelope.keyVersion
                        var unwrapped: ByteArray? = null
                        try {
                            unwrapped = keyManager.unwrapDeviceEnvelope(session.userId, deviceId, stored.envelope)
                            verifyKeys(session, bootstrap, candidates + (version to unwrapped))
                            candidates[version] = unwrapped; unwrapped = null
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            settings.quarantineKey(version, "untrusted_device_envelope")
                            _keyWarning.value = "新版本密钥已隔离，需要重新批准"
                        } finally { unwrapped?.fill(0) }
                    }
                    checkApprovalOwner(session)
                    keyManager.saveVerifiedKeys(session.userId, candidates)
                } finally { candidates.filterKeys { it !in keys }.values.forEach { it.fill(0) } }
            }
            trustedBootstrap = bootstrap
            return bootstrap
        } finally { keys.values.forEach { it.fill(0) } }
    }
    private fun deviceAuthenticated(session: PhoneAccountSession, bootstrap: CloudBootstrap, target: RegisteredCloudDevice): Boolean {
        val v = target.attestationKeyVersion ?: return false
        val key = keyManager.getAccountKey(session.userId, v) ?: return false
        return try { CloudProtocolV2.equal(target.attestation, CloudProtocolV2.attestation(key, session.userId,
            bootstrap.libraryEpoch, target.deviceId, target.publicKeySpki.base64Bytes())) } catch (_: Exception) { false }
        finally { key.fill(0) }
    }
    private suspend fun approvalPayload(session: PhoneAccountSession, targetId: String, targetSpki: String): String {
        val bootstrap = validatedBootstrap(session); val keys = localKeys(session)
        try {
            verifyKeys(session, bootstrap, keys)
            require(keys.keys == bootstrap.keyCommitments.map { it.keyVersion }.toSet()) { "缺少部分云密钥版本，请恢复后再批准新设备" }
            val values = JSONArray()
            keys.toSortedMap().forEach { (v, k) -> values.put(JSONObject().put("keyVersion", v).put("envelope",
                CloudKeyEnvelopeCodec.createDeviceEnvelope(k, session.userId, targetId, CloudKeyEnvelopeCodec.decodeP256PublicKey(targetSpki), v).wireJson().put("recipientType", "device").put("recipientDeviceId", targetId).put("createdByDeviceId", deviceId))) }
            val latest = keys.keys.max()
            return JSONObject().put("envelopes", values).put("attestation", JSONObject().put("keyVersion", latest)
                .put("mac", CloudProtocolV2.attestation(keys.getValue(latest), session.userId, bootstrap.libraryEpoch, targetId, targetSpki.base64Bytes()))).toString()
        } finally { keys.values.forEach { it.fill(0) } }
    }
    private suspend fun acceptApprovalPayload(session: PhoneAccountSession, payload: String) {
        val bootstrap = client.bootstrap(session); val data = JSONObject(payload); val envelopes = data.getJSONArray("envelopes")
        val keys = linkedMapOf<Int, ByteArray>()
        try {
            for (i in 0 until envelopes.length()) {
                val item = envelopes.getJSONObject(i); val envelope = item.getJSONObject("envelope").cloudEnvelope()
                require(item.getInt("keyVersion") == envelope.keyVersion && envelope.keyVersion !in keys)
                keys[envelope.keyVersion] = keyManager.unwrapDeviceEnvelope(session.userId, deviceId, envelope)
            }
            verifyKeys(session, bootstrap, keys)
            require(keys.keys == bootstrap.keyCommitments.map { it.keyVersion }.toSet())
            val attest = data.getJSONObject("attestation"); val v = attest.getInt("keyVersion")
            require(v == keys.keys.max() && CloudProtocolV2.equal(attest.getString("mac"), CloudProtocolV2.attestation(keys.getValue(v),
                session.userId, bootstrap.libraryEpoch, deviceId, keyManager.devicePublicKeySpki(session.userId, deviceId).base64Bytes())))
            checkApprovalOwner(session)
            keyManager.saveVerifiedKeys(session.userId, keys)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            checkApprovalOwner(session)
            keys.keys.forEach { settings.quarantineKey(it, "approval_validation_failed") }
            _keyWarning.value = "授权密钥验证失败，已全部丢弃"
            throw error
        } finally { keys.values.forEach { it.fill(0) } }
    }

    /** True while the local private library belongs to another account; uploads stay blocked until resolved. */
    fun libraryOwnerConflict(): Boolean {
        val userId = accountRepository.session.value?.userId ?: return false
        return settings.ownerUserId.let { it != null && it != userId }
    }

    /** Explicit user action only; the clear callback must remove the entire local private library. */
    suspend fun resolveLibraryOwner(merge: Boolean) = syncMutex.withLock {
        val session = requireSession()
        if (!merge) { repository.clearLibrary(); noteRepository.clearForAccountSwitch() }
        checkApprovalOwner(session); settings.ownerUserId = session.userId
    }

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
        accountRepository.session.value?.let { session -> keyManager.hasAccountKey(session.userId) &&
            trustedBootstrap?.keyEnvelopes?.any { it.recipientType == "recovery" && it.envelope.keyVersion == keyManager.currentKeyVersion(session.userId) } == true } == true

    fun rssInventoryMode(sourceUrl: String): CloudRssInventoryMode =
        rssInventoryPreferences.mode(sourceUrl)

    fun setRssInventoryMode(sourceUrl: String, mode: CloudRssInventoryMode) {
        rssInventoryPreferences.setMode(sourceUrl, mode)
    }

    suspend fun estimateManualSync(): CloudTransferEstimate {
        val session = requireSession()
        validatedBootstrap(session)
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
        val session = requireSession(); val bootstrap = validatedBootstrap(session)
        ensureApprovalIdentity(session, bootstrap)
        return client.bootstrap(session).also { checkCloudEpoch(session, bootstrap.libraryEpoch); _state.update { current -> current.copy(member = it.member) } }
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
        val expectedEpoch = client.epoch(session)
        val decoded = downloadSnapshotObjects(session, snapshotId)
        checkCloudEpoch(session, expectedEpoch)
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
        checkApprovalOwner(session); settings.markApplied(
            decoded.download.head.sourceDeviceId,
            decoded.download.head.deviceSequence,
            full = true
        )
        checkApprovalOwner(session); settings.markApplied(
            decoded.download.head.sourceDeviceId,
            decoded.download.head.deviceSequence,
            full = false
        )
        client.acknowledge(session, snapshotId, deviceId, expectedEpoch = expectedEpoch)
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
        cache.deleteSnapshot(client.scopeId(session), snapshotId)
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
                    settings.clearScope()
                    client.forgetEpoch(session)
                    uploader.clearScope(client.scopeId(session))
                    cache.clear(client.scopeId(session))
                    recordStore.clear(client.scopeId(session))
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

    suspend fun prepareFirstDevice(): RecoveryKeySetup = approvalMutex.withLock {
        val session = requireSession(); val bootstrap = client.bootstrap(session)
        requireCloudFirstActivationAllowed(bootstrap)
        keyManager.createRecoverySetup(session.userId, keyVersion = bootstrap.keyVersionFloor + 1).also {
            pendingSetup?.pendingKey?.fill(0); pendingSetup = it; pendingSetupOwner = session.userId; pendingSetupEpoch = bootstrap.libraryEpoch
        }
    }
    fun discardRecoverySetup() {
        pendingSetup?.pendingKey?.fill(0); pendingSetup = null; pendingSetupOwner = null; pendingSetupEpoch = null
    }
    private fun checkSetup(session: PhoneAccountSession, setup: RecoveryKeySetup, words: List<String>) {
        check(pendingSetup === setup && pendingSetupOwner == session.userId && pendingSetupEpoch == client.epoch(session)) { "恢复词准备已失效，请重新开始" }
        require(setup.pendingKey.size == 32 && setup.words.map { it.lowercase() } == words.map { it.trim().lowercase() }) { "24词恢复密钥确认不一致" }
        settings.requireUploadOwner(session.userId)
    }
    suspend fun activateFirstDevice(setup: RecoveryKeySetup, confirmedWords: List<String>) = approvalMutex.withLock {
        val session = requireSession(); val bootstrap = client.bootstrap(session); checkSetup(session, setup, confirmedWords)
        val key = setup.pendingKey; val version = setup.envelope.keyVersion
        if (bootstrap.keyCommitments.isNotEmpty()) {
            verifyKeys(session, bootstrap, mapOf(version to key))
            require(isCloudKeySetupComplete(bootstrap.keyEnvelopes, bootstrap.devices, deviceId, version))
            keyManager.saveAccountKey(session.userId, version, key, true); key.fill(0); pendingSetup = null
            return@withLock
        }
        requireCloudFirstActivationAllowed(bootstrap)
        client.registerDevice(session, deviceId, "手机 · ${Build.MODEL}", keyManager.devicePublicKeySpki(session.userId, deviceId), version,
            CloudProtocolV2.attestation(key, session.userId, bootstrap.libraryEpoch, deviceId, keyManager.devicePublicKeySpki(session.userId, deviceId).base64Bytes()), version, expectedEpoch = bootstrap.libraryEpoch)
        client.rotate(session, rotationBody(session, bootstrap, setup, null), bootstrap.libraryEpoch)
        checkApprovalOwner(session)
        val accepted = client.bootstrap(session)
        verifyKeys(session, accepted, mapOf(version to key))
        keyManager.saveAccountKey(session.userId, version, key, true)
        key.fill(0); pendingSetup = null
    }
    suspend fun recoverWithWords(words: List<String>) = approvalMutex.withLock {
        val session = requireSession(); val bootstrap = client.bootstrap(session)
        val recovery = bootstrap.keyEnvelopes.filter { it.recipientType == "recovery" }.map { it.envelope }
        if (bootstrap.keyCommitments.isEmpty()) {
            val entropy = RecoveryWords.decode(words)
            val recovered = linkedMapOf<Int, ByteArray>()
            try {
                recovery.sortedBy { it.keyVersion }.forEach { recovered[it.keyVersion] = CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(it, session.userId, entropy) }
                require(recovered.isNotEmpty()) { "账号没有恢复密钥" }
                if (!legacyMigrationHasCompleteKeySet(bootstrap, recovered.keys)) throw CloudLegacyMigrationPendingException()
                client.registerDevice(session, deviceId, "手机 · ${Build.MODEL}", keyManager.devicePublicKeySpki(session.userId, deviceId), recovered.keys.max(), expectedEpoch = bootstrap.libraryEpoch)
                val rows = recovered.toSortedMap().map { (v,k) ->
                    val commitment = CloudProtocolV2.commitment(k, session.userId, bootstrap.libraryEpoch, v)
                    CloudKeyCommitment(v, commitment, if(v == recovered.keys.min()) null else CloudProtocolV2.rotationProof(requireNotNull(recovered[v-1]), session.userId, bootstrap.libraryEpoch, v, commitment))
                }
                storeLegacyCommitments(client, session, deviceId, rows, bootstrap.libraryEpoch)
                val current = client.bootstrap(session); verifyKeys(session, current, recovered); checkApprovalOwner(session)
                keyManager.saveVerifiedKeys(session.userId, recovered)
            } finally { entropy.fill(0); recovered.values.forEach { it.fill(0) } }
        } else keyManager.recoverWithWords(session.userId, words, recovery) { verifyKeys(session, bootstrap, it); checkApprovalOwner(session) }
        checkCloudEpoch(session, bootstrap.libraryEpoch)
        registerCurrentDevice(session, bootstrap.libraryEpoch); storeEnvelopeForCurrentDevice(session, bootstrap.libraryEpoch)
    }
    private fun checkCloudEpoch(session: PhoneAccountSession, epoch: String) {
        checkApprovalOwner(session)
        if (client.knownEpoch(session) != epoch) throw CloudTrustException()
    }
    private fun checkApprovalOwner(session: PhoneAccountSession) {
        check(accountRepository.session.value?.userId == session.userId) { "账号已切换，请重新操作" }
    }
    suspend fun requestDeviceApproval(expectedEpoch: String? = null): CloudBootstrap {
        val session = requireSession(); val bootstrap = client.bootstrap(session)
        checkCloudEpoch(session, expectedEpoch ?: bootstrap.libraryEpoch)
        registerCurrentDevice(session, expectedEpoch ?: bootstrap.libraryEpoch)
        update(CloudSyncPhase.NEEDS_RECOVERY, "请使用恢复词或设备审批 v2", bootstrap.member)
        return bootstrap
    }
    suspend fun acceptDeviceApproval(): Boolean {
        val session = requireSession(); validatedBootstrap(session)
        return keyManager.hasAccountKey(session.userId)
    }
    suspend fun approveDevice(recipientDeviceId: String) {
        val session = requireSession(); val bootstrap = validatedBootstrap(session)
        require(!recipientDeviceId.startsWith("reader-migration-")) { "阅读迁移虚拟设备不能审批" }
        val target = bootstrap.devices.firstOrNull { it.deviceId == recipientDeviceId && it.revokedAt == null } ?: error("设备不存在")
        require(deviceAuthenticated(session, bootstrap, target)) { "设备需要重新批准，请核对显示码" }
        distributeMissing(session, bootstrap, listOf(target))
    }
    private suspend fun distributeMissing(session: PhoneAccountSession, bootstrap: CloudBootstrap, targets: List<RegisteredCloudDevice> = bootstrap.devices, expectedEpoch: String = bootstrap.libraryEpoch) {
        checkCloudEpoch(session, expectedEpoch)
        val rejected = mutableListOf<RegisteredCloudDevice>()
        targets.filter { it.revokedAt == null && !it.deviceId.startsWith("reader-migration-") }.forEach { target ->
            if (!deviceAuthenticated(session, bootstrap, target)) { rejected += target; return@forEach }
            keyManager.keyVersions(session.userId).forEach { version ->
                if (bootstrap.keyEnvelopes.any { it.recipientDeviceId == target.deviceId && it.envelope.keyVersion == version }) return@forEach
                val key = requireNotNull(keyManager.getAccountKey(session.userId, version))
                try { checkApprovalOwner(session); client.storeDeviceEnvelope(session, target.deviceId, deviceId,
                    CloudKeyEnvelopeCodec.createDeviceEnvelope(key, session.userId, target.deviceId, CloudKeyEnvelopeCodec.decodeP256PublicKey(target.publicKeySpki), version), expectedEpoch) }
                finally { key.fill(0) }
            }
        }
        _needsReapproval.value = bootstrap.devices.filter { it.revokedAt == null && !it.deviceId.startsWith("reader-migration-") && !deviceAuthenticated(session, bootstrap, it) }
    }
    private fun rotationBody(session: PhoneAccountSession, bootstrap: CloudBootstrap, setup: RecoveryKeySetup, previous: ByteArray?): JSONObject {
        return cloudRotationBody(session.userId, bootstrap.libraryEpoch, deviceId,
            keyManager.devicePublicKeySpki(session.userId, deviceId), setup, previous)
    }
    /** R2: one recovery entropy covers every version; retry an interrupted atomic write in memory. */
    suspend fun rotateAccountKey(words: List<String>): Int = approvalMutex.withLock {
        val session = requireSession(); val initial = validatedBootstrap(session)
        registerCurrentDevice(session, initial.libraryEpoch)
        val bootstrap = client.bootstrap(session)
        checkCloudEpoch(session, initial.libraryEpoch)
        val latest = bootstrap.keyEnvelopes.filter { it.recipientType == "recovery" }.maxBy { it.envelope.keyVersion }.envelope
        val entropy = RecoveryWords.decode(words)
        try {
            val recovered = CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(latest, session.userId, entropy)
            val current = requireNotNull(keyManager.getAccountKey(session.userId, latest.keyVersion))
            try { require(recovered.contentEquals(current)) { "恢复词不匹配" } } finally { recovered.fill(0); current.fill(0) }
            distributeMissing(session, bootstrap)
            checkApprovalOwner(session); settings.requireUploadOwner(session.userId)
            var setup = pendingRotation?.takeIf { pendingRotationOwner == session.userId && pendingRotationEpoch == bootstrap.libraryEpoch }
            if (setup == null) {
                pendingRotation?.pendingKey?.fill(0)
                val v = keyManager.currentKeyVersion(session.userId) + 1
                val key = ByteArray(32).also(SecureRandom()::nextBytes)
                setup = RecoveryKeySetup(pendingKey = key, words = words.toList(), envelope = CloudKeyEnvelopeCodec.createRecoveryEnvelope(key, session.userId, v, entropy))
                pendingRotation = setup; pendingRotationOwner = session.userId; pendingRotationEpoch = bootstrap.libraryEpoch
            }
            val previous = requireNotNull(keyManager.getAccountKey(session.userId, setup.envelope.keyVersion - 1))
            try {
                val existing = bootstrap.keyCommitments.firstOrNull { it.keyVersion == setup.envelope.keyVersion }
                if (existing == null) client.rotate(session, rotationBody(session, bootstrap, setup, previous), bootstrap.libraryEpoch)
                else if (!CloudProtocolV2.equal(existing.commitment, CloudProtocolV2.commitment(setup.pendingKey, session.userId, bootstrap.libraryEpoch, setup.envelope.keyVersion))) {
                    setup.pendingKey.fill(0); pendingRotation = null
                    error("轮换版本已被其他设备使用，请重新开始")
                }
            } catch (e: CloudHttpException) {
                if (e.status == 409) { setup.pendingKey.fill(0); pendingRotation = null }
                throw e
            } finally { previous.fill(0) }
            checkApprovalOwner(session)
            val keys = localKeys(session).toMutableMap(); keys[setup.envelope.keyVersion] = setup.pendingKey.copyOf()
            try { verifyKeys(session, client.bootstrap(session), keys); keyManager.saveVerifiedKeys(session.userId, keys) }
            finally { keys.values.forEach { it.fill(0) } }
            setup.pendingKey.fill(0); pendingRotation = null
            try { distributeMissing(session, client.bootstrap(session), expectedEpoch = bootstrap.libraryEpoch) }
            catch (e: Exception) { if (e is CancellationException) throw e; _keyWarning.value = "密钥已轮换，设备信封等待补发" }
            setup.envelope.keyVersion
        } finally { entropy.fill(0) }
    }
    suspend fun resumeKeyDistribution() { val session = requireSession(); distributeMissing(session, validatedBootstrap(session)) }

    suspend fun revokeDevice(deviceId: String) {
        require(deviceId != this.deviceId) { "不能在当前设备上撤销自己" }
        client.revokeDevice(requireSession(), deviceId)
    }

    suspend fun reregisterCurrentDevice() = approvalMutex.withLock {
        check(_deviceRegistrationConflict.value) { "本机无需重新登记" }
        val session = requireSession(); val expectedEpoch = client.epoch(session)
        reregisterCloudDevice(
            revokeRemote = { client.revokeDevice(session, deviceId, expectedEpoch); checkCloudEpoch(session, expectedEpoch) },
            replaceIdentity = { keyManager.clearDeviceKey(session.userId, deviceId); clearCloudScope(session) },
            registerReplacement = {
                val bootstrap = client.bootstrap(session)
                checkCloudEpoch(session, expectedEpoch)
                registerCurrentDevice(session, bootstrap.libraryEpoch)
            }
        )
        update(CloudSyncPhase.NEEDS_RECOVERY, "本机已重新登记，请使用恢复词或设备授权")
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
            validatedBootstrap(session)
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
                val encrypted = cache.loadManifest(client.scopeId(session), head.id) ?: run {
                    val remote = client.snapshot(session, head.id, manifestOnly = true)
                    client.download(remote.manifestSignedUrl, head.manifestSizeBytes, head.manifestSha256)
                }
                codec.decryptManifest(key, head.id, encrypted).also {
                    validateCloudManifestHead(it, head)
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
                val encrypted = cache.loadChunk(client.scopeId(session), chunk.ciphertextSha256) ?: run {
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
                cache.prune(client.scopeId(session))
                var bootstrap = validatedBootstrap(session)
                val syncEpoch = bootstrap.libraryEpoch
                pausedResult()?.let { return@run it }
                if (!keyManager.hasAccountKey(session.userId)) {
                    bootstrap = requestDeviceApproval(syncEpoch)
                    if (!acceptDeviceApproval()) {
                        error("当前设备尚未获得云端解密密钥，请在旧设备批准后重试")
                    }
                    bootstrap = client.bootstrap(session)
                    checkCloudEpoch(session, syncEpoch)
                }
                run {
                    val operationEpoch = bootstrap.libraryEpoch
                    registerCurrentDevice(session, operationEpoch)
                    bootstrap = client.bootstrap(session)
                    checkCloudEpoch(session, operationEpoch)
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
                checkCloudEpoch(session, bootstrap.libraryEpoch)
                settings.ensureFullBodyRestoreVersion(session.userId)
                settings.requireUploadOwner(session.userId)
                try { recordSync.sync(session, true, bootstrap.libraryEpoch); _recordError.value = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    checkCloudEpoch(session, bootstrap.libraryEpoch)
                    if (e is CloudTrustException || (e is CloudHttpException && e.detail.contains("library_epoch_mismatch"))) throw e
                    _recordError.value = if (e is CloudHttpException && e.detail.contains("count_limit")) "云记录条数达到上限" else if (e is CloudHttpException && e.status == 413) "云记录额度不足" else "记录通道暂不可用，快照继续同步"
                }
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
                        checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markApplied(head.sourceDeviceId, head.deviceSequence, full = true)
                        checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markApplied(head.sourceDeviceId, head.deviceSequence, full = false)
                        client.acknowledge(session, head.id, deviceId, expectedEpoch = bootstrap.libraryEpoch)
                    } else if (outcome.stateApplied) {
                        checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markApplied(head.sourceDeviceId, head.deviceSequence, full = false)
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
                val compacted = repository.compactCloudExcerpts { checkCloudEpoch(session, bootstrap.libraryEpoch) }
                traceSync("metadata-compacted:$compacted")
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
                run {
                    settings.requireUploadOwner(session.userId)
                    val currentKeyVersion = keyManager.currentKeyVersion(session.userId)
                    val accountKey = keyManager.getAccountKey(session.userId, currentKeyVersion)
                        ?: error("当前账号主密钥不存在")
                    val freshHeads = client.snapshotHeads(session)
                    checkCloudEpoch(session, bootstrap.libraryEpoch)
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
                cache.prune(client.scopeId(session))
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
                    invalidateEpoch(requireSession())
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
        val expectedEpoch = client.epoch(session)
        val download = client.snapshot(session, head.id)
        val cachedManifest = cache.loadManifest(client.scopeId(session), head.id)
        val encryptedManifest = cachedManifest
            ?: client.download(
                download.manifestSignedUrl,
                head.manifestSizeBytes,
                head.manifestSha256
            ).also {
                cache.storeManifest(client.scopeId(session),
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
        val manifest = codec.decryptManifest(accountKey, head.id, encryptedManifest).also { validateCloudManifestHead(it, head) }
        cache.recordManifestReferences(client.scopeId(session),
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
                checkCloudEpoch(session, expectedEpoch)
                if (cache.loadChunk(client.scopeId(session), descriptor.ciphertextSha256) == null) {
                    val remote = chunkUrls[descriptor.ciphertextSha256]
                        ?: error("云快照缺少加密块 ${descriptor.ciphertextSha256}")
                    client.download(remote.signedUrl, remote.sizeBytes, remote.sha256).also {
                        cache.storeChunk(client.scopeId(session), remote.sha256, it)
                        downloadedBytes += it.size
                    }
                }
            }
        yieldToReading()
        checkCloudEpoch(session, expectedEpoch)
        update(CloudSyncPhase.MERGING, "正在合并远端快照")
        var changed = false
        var rssPending = 0
        // Restore one logical object at a time: retaining the archive, relay and
        // RSS JSON together can exhaust the heap on a full phone library.
        val restoreOrder = listOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT, RSS_STATE_OBJECT, NOTES_STATE_OBJECT, NOTES_ARCHIVE_OBJECT)
        for (descriptor in selectedObjects.sortedBy { restoreOrder.indexOf(it.name) }) {
            yieldToReading()
            checkCloudEpoch(session, expectedEpoch)
            traceSync("object-start:${descriptor.name}")
            val bytes = codec.restoreObjects(accountKey, manifest.copy(objects = listOf(descriptor))) {
                sha -> cache.loadChunk(client.scopeId(session), sha) ?: error("本地缺少加密块 $sha")
            }.getValue(descriptor.name)
            when (descriptor.name) {
                PRIVATE_LIBRARY_OBJECT -> {
                    val result = backupService.restorePrivateLibrary(bytes, BackupImportMode.MERGE) { checkCloudEpoch(session, expectedEpoch) }
                    changed = changed || result.changedArticleCount > 0 || result.changedSourceCount > 0 || result.changedSavedItemCount > 0
                }
                RELAY_LIBRARY_OBJECT -> changed = backupService.restoreCloudRelayLibrary(bytes, { checkCloudEpoch(session, expectedEpoch) }, { yieldToReading() }) > 0 || changed
                RSS_STATE_OBJECT -> {
                    val applied = backupService.applyCloudRssState(bytes, createMissing = true) { checkCloudEpoch(session, expectedEpoch) }
                    changed = changed || applied.first > 0
                    rssPending = applied.second
                }
                NOTES_STATE_OBJECT -> {
                    NoteCloudStateCodec.decode(bytes).forEach { checkCloudEpoch(session, expectedEpoch); noteRepository.applyRemote(it, it.modifiedBy) }
                    changed = true
                }
                NOTES_ARCHIVE_OBJECT -> noteTransfer.restoreAssetsZip(bytes)
            }
            traceSync("object-done:${descriptor.name}")
        }
        cache.storeManifest(client.scopeId(session),
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
        validatedBootstrap(session)
        val download = client.snapshot(session, snapshotId)
        val cachedManifest = cache.loadManifest(client.scopeId(session), snapshotId)
        val encryptedManifest = cachedManifest
            ?: client.download(
                download.manifestSignedUrl,
                download.head.manifestSizeBytes,
                download.head.manifestSha256
            ).also {
                cache.storeManifest(client.scopeId(session),
                    snapshotId,
                    it,
                    markAsLocalHead = false
                )
            }
        val accountKey = keyManager.getAccountKey(session.userId, download.head.keyVersion)
            ?: error("缺少第${download.head.keyVersion}版账号密钥")
        val manifest = codec.decryptManifest(accountKey, snapshotId, encryptedManifest).also { validateCloudManifestHead(it, download.head) }
        cache.recordManifestReferences(client.scopeId(session),
            snapshotId,
            manifest.allChunks.map(CloudChunkDescriptor::ciphertextSha256)
        )
        val remoteChunks = download.chunks.associateBy(CloudDownloadObject::sha256)
        manifest.allChunks.forEach { descriptor ->
            if (cache.loadChunk(client.scopeId(session), descriptor.ciphertextSha256) == null) {
                val remote = remoteChunks[descriptor.ciphertextSha256]
                    ?: error("云快照缺少加密块 ${descriptor.ciphertextSha256}")
                client.download(remote.signedUrl, remote.sizeBytes, remote.sha256).also {
                    cache.storeChunk(client.scopeId(session), remote.sha256, it)
                }
            }
        }
        val objects = codec.restoreObjects(accountKey, manifest) { sha ->
            cache.loadChunk(client.scopeId(session), sha) ?: error("本地缺少加密块 $sha")
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
        settings.requireUploadOwner(session.userId)
        verifyLocalKeys(session, requireNotNull(trustedBootstrap))
        val previous = loadLatestManifest(client.scopeId(session), accountKey, keyVersion)
        val privateFingerprint = if (fullTransfer) backupService.privateCloudFingerprint() else null
        val pendingManifest = cache.loadPendingManifest(client.scopeId(session))?.let { (id, bytes) ->
            runCatching { codec.decryptManifest(accountKey, id, bytes) }.getOrNull()
                ?.takeIf { it.keyVersion == keyVersion }
        }
        // A retry can carry an authenticated pending body when every block is still local.
        val bodySource = listOfNotNull(previous, pendingManifest).firstOrNull { candidate ->
            candidate.settings.optString("privateBodyFingerprintV1") == privateFingerprint &&
                ((candidate === previous && heads.any { it.id == candidate.snapshotId }) || candidate.allChunks.all { cache.loadChunk(client.scopeId(session), it.ciphertextSha256) != null })
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
            cache.loadManifest(client.scopeId(session), head.id)?.let { bytes ->
                runCatching { codec.decryptManifest(accountKey, head.id, bytes).also { validateCloudManifestHead(it, head) } }.getOrNull()
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
            deviceSequence = run { checkCloudEpoch(session, bootstrap.libraryEpoch); settings.nextSequence(serverSequence) },
            logicalObjects = logicalObjects.map { it.copy(incrementalChunks = true) },
            parentHeads = parentHeads,
            observedHeads = observedHeads,
            previousManifest = previous,
            carryForwardObjects = carried,
            reusableManifests = reusableManifests + listOfNotNull(pendingManifest),
            snapshotSettings = snapshotSettings,
            onReusedChunk = { descriptor, materialize ->
                if (cache.loadChunk(client.scopeId(session), descriptor.ciphertextSha256) == null) {
                    cache.storeChunk(client.scopeId(session), descriptor.ciphertextSha256, materialize())
                }
            }
        )
        encrypted.newCiphertextChunks.forEach { (sha, bytes) ->
            cache.storeChunk(client.scopeId(session), sha, bytes)
        }
        checkCloudEpoch(session, bootstrap.libraryEpoch)
        cache.storePendingManifest(client.scopeId(session), encrypted.manifest.snapshotId, encrypted.encryptedManifest,
            encrypted.manifest.allChunks.map { it.ciphertextSha256 })
        traceSync("upload-chunks:new=${encrypted.newCiphertextChunks.size}:total=${encrypted.manifest.allChunks.size}:newBytes=${encrypted.newCiphertextChunks.values.sumOf { it.size.toLong() }}")
        update(CloudSyncPhase.UPLOADING, "正在预留云空间", bootstrap.member)
        val reservation = client.reserveSnapshot(session, encrypted, settings.retentionDays, bootstrap.libraryEpoch)
        traceSync("upload-plan:objects=${reservation.missingObjects.size}:bytes=${reservation.missingObjects.sumOf { it.sizeBytes }}")
        var uploadedBytes = 0L
        var uploadedObjects = 0
        val uploadSlots = kotlinx.coroutines.sync.Semaphore(3)
        val progressLock = Any()
        kotlinx.coroutines.coroutineScope {
        reservation.missingObjects.map { target -> async { uploadSlots.withPermit {
            checkCloudEpoch(session, bootstrap.libraryEpoch)
            val bytes = if (target.kind == "manifest") {
                encrypted.encryptedManifest
            } else {
                cache.loadChunk(client.scopeId(session), target.sha256)
                    ?: error("本地缺少待上传加密块 ${target.sha256}")
            }
            var attempt = 0
            var sent = false
            while (true) {
                checkCloudEpoch(session, bootstrap.libraryEpoch)
                try {
                    // Late blocks need a fresh URL on slow links.
                    val fresh = client.refreshUpload(session, target, bootstrap.libraryEpoch)
                    if (!fresh.alreadyUploaded) {
                        client.uploadCiphertext(session, fresh, bytes, bootstrap.libraryEpoch)
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
        client.completeSnapshot(session, encrypted, bootstrap.libraryEpoch)
        traceSync("upload-verify:done")
        cache.storeManifest(client.scopeId(session),
            encrypted.manifest.snapshotId,
            encrypted.encryptedManifest,
            markAsLocalHead = true
        )
        cache.recordManifestReferences(client.scopeId(session),
            encrypted.manifest.snapshotId,
            encrypted.manifest.allChunks.map(CloudChunkDescriptor::ciphertextSha256)
        )
        cache.clearPendingManifest(client.scopeId(session), encrypted.manifest.snapshotId)
        cache.prune(client.scopeId(session))
        checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markApplied(deviceId, encrypted.manifest.deviceSequence, full = fullTransfer)
        checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markApplied(deviceId, encrypted.manifest.deviceSequence, full = false)
        checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markUploaded(contentHash, parentHeads, full = fullTransfer)
        if (fullTransfer) {
            val stateObjects = logicalObjects.filter { it.name == RSS_STATE_OBJECT || it.name == NOTES_STATE_OBJECT || it.name == NOTES_ARCHIVE_OBJECT }
            val bodyObjects = encrypted.manifest.objects.filter {
                it.name == PRIVATE_LIBRARY_OBJECT || it.name == RELAY_LIBRARY_OBJECT
            }
            checkCloudEpoch(session, bootstrap.libraryEpoch); settings.markUploaded(
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

    private suspend fun ensureApprovalIdentity(session: PhoneAccountSession, bootstrap: CloudBootstrap) {
        if (!keyManager.hasAccountKey(session.userId)) return
        val device = bootstrap.devices.firstOrNull { it.deviceId == deviceId }
        if (device?.revokedAt != null) { invalidateEpoch(session); throw CloudTrustException() }
        registerCurrentDevice(session, bootstrap.libraryEpoch)
        storeEnvelopeForCurrentDevice(session, bootstrap.libraryEpoch)
    }
    private suspend fun registerCurrentDevice(session: PhoneAccountSession, expectedEpoch: String? = client.knownEpoch(session)) {
        val bootstrap = trustedBootstrap ?: client.bootstrap(session)
        val operationEpoch = expectedEpoch ?: bootstrap.libraryEpoch
        checkCloudEpoch(session, operationEpoch)
        val spki = keyManager.devicePublicKeySpki(session.userId, deviceId)
        val version = if (keyManager.hasAccountKey(session.userId)) keyManager.currentKeyVersion(session.userId)
            else bootstrap.keyCommitments.maxOfOrNull { it.keyVersion } ?: (bootstrap.keyVersionFloor + 1)
        val key = keyManager.getAccountKey(session.userId, version)
        try {
            if (key != null) verifyLocalKeys(session, bootstrap)
            client.registerDevice(session, deviceId, "手机 · ${Build.MODEL}", spki, version,
                key?.let { CloudProtocolV2.attestation(it, session.userId, bootstrap.libraryEpoch, deviceId, spki.base64Bytes()) }, if (key == null) null else version, operationEpoch)
        } finally { key?.fill(0) }
    }
    private suspend fun storeEnvelopeForCurrentDevice(session: PhoneAccountSession, expectedEpoch: String? = client.knownEpoch(session)) {
        val bootstrap = validatedBootstrap(session)
        checkCloudEpoch(session, expectedEpoch ?: bootstrap.libraryEpoch)
        val own = bootstrap.devices.firstOrNull { it.deviceId == deviceId } ?: return
        require(deviceAuthenticated(session, bootstrap, own)) { "设备需要重新批准" }
        distributeMissing(session, bootstrap, listOf(own))
    }

    private fun requireSession(): PhoneAccountSession =
        (accountRepository.session.value ?: error("请先登录腕上RSS账号")).also { bindScope(it); settings.claimLegacyOwner(it.userId) }

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
