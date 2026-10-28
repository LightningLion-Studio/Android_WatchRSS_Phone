package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.pairing.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

import android.content.Context
import android.os.Build
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.data.backup.CloudRestoreWarning
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
    val lastCompletedAt: Long = 0,
    val pendingSnapshotUntil: Long = 0,
    val errorCode:String? = null
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
    private val legacyDeviceId: String? = null,
    private val formatWritePolicy:CloudFormatWritePolicy = CloudFormatWritePolicy(),
    private val migrationWorkerTrust:CloudWorkerTrust = CloudWorkerTrust.bundled
) {
    private val appContext = context.applicationContext
    private val traceLog = com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog(context.applicationContext)
    private val debugTrace = java.io.File(context.cacheDir, "cloud-sync-debug.trace")
    private fun traceSync(stage: String) {
        val safe = safeCloudTrace(stage)
        runCatching {
            if (debugTrace.length() > 65536) debugTrace.writeText("")
            debugTrace.appendText("${System.currentTimeMillis()} $safe\n")
            android.util.Log.i("WatchRSSCloud", safe)
            traceLog.appendEvent("cloud.sync",fields=mapOf("stage" to safe))
        }
    }
    private val recordStore = PhoneCloudRecordStore(context.applicationContext)
    private val _formatStatus=MutableStateFlow(CloudFormatStatus())
    val formatStatus:kotlinx.coroutines.flow.StateFlow<CloudFormatStatus> = _formatStatus
    private fun observeFormat(session:PhoneAccountSession,manifest:CloudSnapshotManifest) {
        val scope=client.scopeId(session)
        recordStore.saveDocument(scope,"snapshot:${manifest.snapshotId}","0",manifest.toRawString(),manifest.access)
        manifest.objects.forEach {recordStore.saveDocument(scope,"object:${manifest.snapshotId}:${it.name}","0",it.toRawString(),it.access)}
        _formatStatus.value=recordStore.formatStatus(scope)
        manifest.requireReadable()
    }
    private fun pendingFormat(session:PhoneAccountSession,id:String) {
        recordStore.pending(client.scopeId(session),id,"local-edit-preserved")
        _formatStatus.value=recordStore.formatStatus(client.scopeId(session))
    }
    private val recordSync = PhoneCloudRecordSync(client, recordStore,
        backupService::cloudRecordPages, { session, kind, value ->
            if (kind != "rss-state") backupService.applyCloudRecord(kind, value) { checkApprovalOwner(session) }
        },
        keyManager::currentKeyVersion, keyManager::getAccountKey, ::checkApprovalOwner,
        anchorVersion = { trustedBootstrap?.keyCommitments?.minOfOrNull { it.keyVersion } ?: error("缺少锚定密钥") },
        refreshKeys = { session -> validatedBootstrap(session) },
        keepArticle = backupService::keepCloudArticle,
        canonicalArticleId = backupService::canonicalCloudArticleId,
        bookForArticle = backupService::bookRecordForLegacyArticle,
        afterPull = { session -> backupService.applyRssInteractions(recordStore.rssStates(client.scopeId(session))) { checkApprovalOwner(session) } })
    private val noteTransfer = NoteImportExportService(context, noteRepository)
    private val syncMutex = CloudSyncSingleFlight()
    private val _state = MutableStateFlow(PhoneCloudSyncState())
    val state: StateFlow<PhoneCloudSyncState> = _state

    private val pairingEpochState = MutableStateFlow<String?>(null)
    val pairingChanges: kotlinx.coroutines.flow.Flow<Unit> = merge(keyManager.changes.map { Unit }, pairingEpochState.map { Unit })
    fun pairingEpoch(): String? = accountRepository.session.value?.let { client.knownEpoch(it) }
    val pairingBackendBaseUrl: String get() = client.backendBaseUrl
    private var trustedBootstrap: CloudBootstrap? = null
    private val approvalMutex = Mutex()
    private var pendingSetup: RecoveryKeySetup? = null
    private var pendingSetupOwner: String? = null
    private var pendingSetupEpoch: String? = null
    private var pendingRotation: RecoveryKeySetup? = null
    private var pendingRotationOwner: String? = null
    private var pendingRotationEpoch: String? = null
    private fun migrationContext():CloudMigrationContext {
        val session=requireSession()
        return CloudMigrationContext(client.scopeId(session),session.userId,deviceId,client.epoch(session),requireNotNull(client.knownContentGeneration(session)))
    }
    private val _migrationState=MutableStateFlow(CloudMigrationUiState("unavailable",errorCode="migration_feature_disabled"))
    val formatMigrationState:StateFlow<CloudMigrationUiState> = _migrationState
    private var currentMigration:Pair<CloudMigrationContext,CloudMigrationInitiator>?=null
    val formatMigration:CloudMigrationInitiator get() = synchronized(this) {
        val frozen=migrationContext()
        currentMigration?.takeIf{it.first==frozen}?.let{return@synchronized it.second}
        fun fence() { require(migrationContext()==frozen) { "migration_scope_changed" };checkApprovalOwner(requireSession()) }
        val initiator=CloudMigrationInitiator(::migrationContext,PhoneCloudMigrationApi(client,::requireSession,::fence),CloudMigrationPreferences(appContext),migrationWorkerTrust,
            inputKey={ input -> fence();requireNotNull(keyManager.getAccountKey(frozen.userId,input.keyVersion)) },
            publisher=object:CloudMigrationPublisher {
                override suspend fun retryPublication(draft:CloudMigrationDraft,intent:JSONObject) = retryMigrationPublication(draft,intent)
                override suspend fun publish(draft:CloudMigrationDraft,jobId:String,receiptHash:String,batchId:String,outputs:List<CloudMigrationOutput>,root:ByteArray,validation:JSONObject,beforeComplete:(String,JSONObject)->Unit):String? =
                    publishMigration(draft,jobId,receiptHash,batchId,outputs,root,validation,beforeComplete)
            },policy=formatWritePolicy,stateOverride=_migrationState,observer=CloudMigrationObserver { event ->
                traceLog.appendEvent("cloud.r48",fields=event.fields())
                android.util.Log.i("WatchRSSCloudR48",org.json.JSONObject(event.fields()).toString())
            })
        currentMigration=frozen to initiator
        initiator
    }

    /** UI supplies only selected authenticated logical names, never arbitrary descriptors or keys. */
    suspend fun prepareFormatMigration(snapshotId:String,names:Set<String>,transform:JSONObject,mode:String="sample"):CloudMigrationDraft = withContext(Dispatchers.IO) {
        val session=requireSession();val bootstrap=validatedBootstrap(session);checkCloudEpoch(session,bootstrap.libraryEpoch)
        val frozen=migrationContext()
        fun fence() { require(migrationContext()==frozen) { "migration_scope_changed" };checkApprovalOwner(session) }
        fence()
        val remote=client.snapshot(session,snapshotId);fence()
        val encrypted=client.download(remote.manifestSignedUrl,remote.head.manifestSizeBytes,remote.head.manifestSha256);fence()
        val key=requireNotNull(keyManager.getAccountKey(session.userId,remote.head.keyVersion))
        try {
            val manifest=CloudSnapshotReuse.authenticate(codec,key,remote.head,encrypted)
            manifest.requireReadable()
            require(names.isNotEmpty() && names.all{n->manifest.objects.any{it.name==n}})
            val selected=manifest.objects.filter{it.name in names}
            require(selected.sumOf{it.chunks.sumOf{c->c.ciphertextBytes.toLong()}}<=8_388_608) { "migration_not_suitable" }
            cache.storeManifest(client.scopeId(session),snapshotId,encrypted,markAsLocalHead=false)
            val inputs=selected.map { descriptor ->
                require(descriptor.access==CloudFormatAccess.WRITABLE && descriptor.entryRef==null && descriptor.originalBytes<=8_388_608)
                val type=when {
                    descriptor.name.startsWith("private/articles/") -> "private-article-body"
                    descriptor.privateBook!=null -> "private-book"
                    descriptor.privateArticle!=null && descriptor.name.startsWith("reader/") -> "private-article-body"
                    else -> error("migration_not_suitable") // Unknown app_meta/Cookie scope is never granted.
                }
                val bytes=materializeObject(session,key,manifest,descriptor,listOf(remote.head));fence()
                if(descriptor.name.startsWith("private/articles/")) {
                    val index=manifest.objects.single{it.name==CloudPrivateOverlay.NAME}
                    val entry=CloudPrivateOverlay.decode(materializeObject(session,key,manifest,index,listOf(remote.head))).single{it.objectName==descriptor.name}
                    backupService.validatePrivateOverlayArticle(bytes,entry.id)
                }
                val categories=if(type=="private-book")setOf("book-original","book-directory")else if(descriptor.name.endsWith(".wrss"))setOf("article-metadata","article-txt","article-html")else setOf("article-txt")
                CloudMigrationInput(snapshotId,remote.head.manifestSha256,descriptor,CloudSnapshotCodec.sha256(bytes),type,categories,true,remote.head.keyVersion)
            }
            fence()
            CloudMigrationPlanBuilder.create(frozen.userId,frozen.installationId,frozen.epoch,frozen.generation,transform,inputs,System.currentTimeMillis(),mode)
        } finally {key.fill(0)}
    }

    private suspend fun publishMigration(draft:CloudMigrationDraft,jobId:String,receiptHash:String,batchId:String,outputs:List<CloudMigrationOutput>,root:ByteArray,validation:JSONObject,beforeComplete:(String,JSONObject)->Unit):String? = syncMutex.withLock {
        val session=requireSession();val bootstrap=validatedBootstrap(session)
        require(migrationContext().let{it.userId==draft.plan.getString("userId")&&it.epoch==draft.plan.getString("libraryEpoch")&&it.generation==draft.plan.getString("contentGeneration")})
        require(formatWritePolicy.canMigrate(client.formatCapabilities(session))) { "migration_feature_disabled" }
        val keyVersion=keyManager.currentKeyVersion(session.userId);val key=requireNotNull(keyManager.getAccountKey(session.userId,keyVersion))
        try {
            val heads=client.snapshotHeads(session)
            val manifests=heads.filter{it.keyVersion==keyVersion && !it.sourceDeviceId.startsWith("reader-migration-")}.map { head ->
                val download=client.snapshot(session,head.id,manifestOnly=true);validateCloudManifestHeadIdentity(head,download.head)
                val bytes=client.download(download.manifestSignedUrl,head.manifestSizeBytes,head.manifestSha256)
                CloudSnapshotReuse.authenticate(codec,key,head,bytes)
            }
            val base=manifests.firstOrNull{it.sourceDeviceId==deviceId} ?: return@withLock null
            // A concurrent head holding any different descriptor wins. Never flatten newer user work.
            val eligible=outputs.filter { output -> manifests.flatMap{it.objects}.filter{it.name==output.input.descriptor.name}.all{it.toRawString()==output.input.descriptor.toRawString()} }
            val rebased=CloudMigrationResult.rebase(base,eligible,key,root,draft.plan.getString("planId"))
            val changed=rebased.filterIndexed{index,d->d.toRawString()!=base.objects[index].toRawString()}
            if(changed.isEmpty())return@withLock null
            if(changed.size!=outputs.size && validation.getString("mode")!="full")return@withLock null
            val actualValidation=if(changed.size==outputs.size)validation else JSONObject().put("mode","full").put("verifiedObjects",changed.size).put("totalObjects",changed.size)
            val carried=CloudSnapshotReuse.preserveObjects(manifests,changed.map{it.name}.toSet(),changed)
            val snapshot=codec.create(key,keyVersion,deviceId,settings.nextSequence(bootstrap.devices.firstOrNull{it.deviceId==deviceId}?.lastSequence ?: 0),emptyList(),
                parentHeads=heads.associate{it.sourceDeviceId to it.id},observedHeads=heads.associate{it.sourceDeviceId to it.deviceSequence},previousManifest=base,
                carryForwardObjects=carried,snapshotSettings=base.settings)
            val migration=JSONObject().put("jobId",jobId).put("planHash",draft.planHash).put("batchId",batchId).put("receiptSha256",receiptHash)
                .put("outputCiphertextSha256s",JSONArray(changed.flatMap{it.chunks}.map{it.ciphertextSha256}.distinct().sorted())).put("validation",actualValidation)
            checkCloudEpoch(session,bootstrap.libraryEpoch)
            val reservation=client.reserveSnapshot(session,snapshot,settings.retentionDays,bootstrap.libraryEpoch,migration)
            require(reservation.missingObjects.all{it.kind=="manifest"}) { "migration_candidate_missing" }
            for(target in reservation.missingObjects) {
                val fresh=client.refreshUpload(session,target,bootstrap.libraryEpoch)
                if(!fresh.alreadyUploaded)client.uploadCiphertext(session,fresh,snapshot.encryptedManifest,bootstrap.libraryEpoch)
            }
            checkCloudEpoch(session,bootstrap.libraryEpoch)
            require(formatWritePolicy.canMigrate(client.formatCapabilities(session))) { "migration_feature_disabled" }
            cache.storeManifest(client.scopeId(session),snapshot.manifest.snapshotId,snapshot.encryptedManifest,markAsLocalHead=false)
            beforeComplete(snapshot.manifest.snapshotId,migration)
            client.completeSnapshot(session,snapshot,bootstrap.libraryEpoch,migration)
            checkCloudEpoch(session,bootstrap.libraryEpoch)
            cache.storeManifest(client.scopeId(session),snapshot.manifest.snapshotId,snapshot.encryptedManifest,markAsLocalHead=true)
            snapshot.manifest.snapshotId
        } finally {key.fill(0)}
    }

    private suspend fun retryMigrationPublication(draft:CloudMigrationDraft,intent:JSONObject):Unit = syncMutex.withLock {
        val session=requireSession();val ctx=migrationContext()
        require(ctx.userId==draft.plan.getString("userId") && ctx.epoch==draft.plan.getString("libraryEpoch") && ctx.generation==draft.plan.getString("contentGeneration"))
        require(formatWritePolicy.canMigrate(client.formatCapabilities(session))) { "migration_feature_disabled" }
        val id=intent.getString("snapshotId")
        val bytes=cache.loadManifest(ctx.scope,id) ?: error("migration_publication_cache_missing")
        val key=requireNotNull(keyManager.getAccountKey(session.userId))
        try {
            val manifest=codec.decryptManifest(key,id,bytes)
            require(manifest.sourceDeviceId==deviceId && CloudSnapshotReuse.scopeCompatible(manifest,JSONObject().put("backendScope",ctx.scope).put("ownerUserId",ctx.userId).put("libraryEpoch",ctx.epoch).put("contentGeneration",ctx.generation)))
            val snapshot=EncryptedCloudSnapshot(manifest,bytes,emptyMap())
            val migration=intent.getJSONObject("migrationContext")
            require(migration.getString("jobId")==intent.getString("jobId") && migration.getString("planHash")==draft.planHash)
            try {client.completeSnapshot(session,snapshot,ctx.epoch,migration)}
            catch(error:CloudHttpException) {
                if(!discardedCloudTransfer(error))throw error
                checkCloudEpoch(session,ctx.epoch)
                val reserve=client.reserveSnapshot(session,snapshot,settings.retentionDays,ctx.epoch,migration)
                require(reserve.missingObjects.all{it.kind=="manifest"}) { "migration_candidate_missing" }
                for(target in reserve.missingObjects) {
                    val fresh=client.refreshUpload(session,target,ctx.epoch)
                    if(!fresh.alreadyUploaded)client.uploadCiphertext(session,fresh,bytes,ctx.epoch)
                }
                checkCloudEpoch(session,ctx.epoch)
                client.completeSnapshot(session,snapshot,ctx.epoch,migration)
            }
            cache.storeManifest(ctx.scope,id,bytes,markAsLocalHead=true)
        } finally {key.fill(0)}
    }

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
    private val _restoreWarnings=MutableStateFlow<List<CloudRestoreWarning>>(emptyList())
    val restoreWarnings: StateFlow<List<CloudRestoreWarning>> = _restoreWarnings
    private val _rssRefreshWarnings=MutableStateFlow<List<String>>(emptyList())
    val rssRefreshWarnings: StateFlow<List<String>> = _rssRefreshWarnings

    private val _recordError = MutableStateFlow<String?>(null)
    val recordError: StateFlow<String?> = _recordError

    init {
        client.allowR48Writes={formatWritePolicy.localWrite}
        client.onUploadTransportEvent = { traceSync("upload-transport:$it") }
        client.checkSession = ::checkApprovalOwner
        client.currentDeviceId = deviceId
        keyManager.bindBackend(client.backendBaseUrl)
        accountRepository.session.value?.let { bindScope(it); settings.claimLegacyOwner(it.userId) }
        accountRepository.onReleaseCloudAuthorization = ::releaseCloudAuthorization
        accountRepository.onClearCloudState = ::discardSessionCloudState
        client.onDeviceKeyConflict = { session -> checkApprovalOwner(session); _deviceRegistrationConflict.value = true }
        client.onEpochMismatch = { session -> invalidateEpoch(session) }
        client.onBootstrap = { session, bootstrap -> observeBootstrap(session, bootstrap) }
        client.onContentMetadata = { session, metadata ->
            bindScope(session)
            if (settings.observeContentGeneration(metadata.generation, metadata.empty) { clearContentSyncState(session) }) {
                traceSync("content-rebuild:metadata")
                _state.update { it.copy(pendingSnapshotUntil = 0) }
            }
        }
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
    /** R45 clears synchronization bookkeeping, never local data, login or key trust. */
    private fun clearContentSyncState(session: PhoneAccountSession) {
        checkApprovalOwner(session)
        currentMigration=null
        _migrationState.value=CloudMigrationUiState("unavailable",errorCode="migration_scope_changed")
        settings.clearLibraryState(preserveDeviceSequence = true)
        recordStore.clear(client.scopeId(session))
        _formatStatus.value=CloudFormatStatus()
        cache.clear(client.scopeId(session))
        uploader.clearScope(client.scopeId(session))
        synchronized(readingManifests) { readingManifests.clear() }
    }
    internal fun discardSessionCloudState(session: PhoneAccountSession) = clearCloudScope(session)
    private fun bindScope(session: PhoneAccountSession) {
        checkApprovalOwner(session); settings.bindScope(client.backendBaseUrl, session.userId)
        _formatStatus.value=recordStore.formatStatus(client.scopeId(session))
        settings.contentGeneration?.let { client.useContentGeneration(session, it) }
    }
    private fun clearCloudScope(session: PhoneAccountSession) {
        currentMigration=null
        _migrationState.value=CloudMigrationUiState("unavailable",errorCode="migration_scope_changed")
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
        _restoreWarnings.value=emptyList();_rssRefreshWarnings.value=emptyList();
        _keyWarning.value = null; _recordError.value = null; _needsReapproval.value = emptyList(); _formatStatus.value=CloudFormatStatus()
        client.forgetEpoch(session)
        pairingEpochState.value = null
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
        pairingEpochState.value = bootstrap.libraryEpoch
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

    /** No new key is generated here: the caller must explicitly open/recover cloud keys first. */
    suspend fun prepareWatchPairingCloud(expectedUserId: String): PairingCloudPreparation {
        val session = requireSession(); check(session.userId == expectedUserId) { "账号已切换，请重新配对" }; val bootstrap = validatedBootstrap(session)
        if (!keyManager.hasAccountKey(session.userId) || keyManager.keyVersions(session.userId).toSet() != bootstrap.keyCommitments.map { it.keyVersion }.toSet()) return PairingCloudPreparation.NeedsKey(bootstrap.libraryEpoch,
            bootstrap.keyCommitments.isNotEmpty() || bootstrap.keyEnvelopes.isNotEmpty() || bootstrap.legacyKeyVersions.isNotEmpty())
        verifyLocalKeys(session, bootstrap)
        ensureApprovalIdentity(session, bootstrap)
        checkCloudEpoch(session, bootstrap.libraryEpoch)
        return PairingCloudPreparation.Ready(bootstrap.libraryEpoch)
    }

    suspend fun grantWatchPairingCloud(qr: WatchPairingQr, preparedSpki: String, expectedEpoch: String, expectedUserId: String): PairingCloudGrant {
        val session = requireSession(); check(session.userId == expectedUserId) { "账号已切换，请重新配对" }; checkCloudEpoch(session, expectedEpoch)
        val bootstrap = validatedBootstrap(session); checkCloudEpoch(session, expectedEpoch)
        val keys = localKeys(session)
        val grant = try { buildPairingCloudGrant(session.userId, deviceId, bootstrap, qr, preparedSpki, keys) }
            finally { keys.values.forEach { it.fill(0) } }
        checkCloudEpoch(session, expectedEpoch)
        if (grant.registered.attestation != grant.attestation || grant.registered.attestationKeyVersion != grant.keyVersion) {
            client.attestPairingWatch(session, grant.registered, grant.attestation, grant.keyVersion, expectedEpoch)
            checkCloudEpoch(session, expectedEpoch)
        }
        for (envelope in grant.envelopes) {
            val exists = bootstrap.keyEnvelopes.any { it.recipientType == "device" && it.recipientDeviceId == qr.deviceId && it.envelope.keyVersion == envelope.keyVersion }
            if (!exists) client.storeDeviceEnvelope(session, qr.deviceId, deviceId, envelope, expectedEpoch)
            checkCloudEpoch(session, expectedEpoch)
        }
        return grant
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
        // Local possession survives process recreation/offline startup. First activation only
        // persists after the server confirms its recovery envelope; every sync still validates
        // the epoch and commitments before using any key.
        accountRepository.session.value?.let { keyManager.hasAccountKey(it.userId) } == true

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
        val uploadBytes = privateArchive.length() +
            128 * 1024L
        privateArchive.delete()
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
            privateArticleCount = if(decoded.objects.containsKey(CloudPrivateOverlay.NAME)) backupService.inspectPrivateOverlayCount(decoded.objects) else privatePreview?.articleCount ?: 0,
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
        settings.ensureRestoreFailureClassificationV2()
        val decoded = downloadSnapshotObjects(session, snapshotId)
        checkCloudEpoch(session, expectedEpoch)
        _restoreWarnings.value=emptyList()
        val privateArchive = decoded.objects[PRIVATE_LIBRARY_OBJECT]
        readRecordsForRestore(session, expectedEpoch)
        require(mode != BackupImportMode.REPLACE || privateArchive != null) {
            "该手表快照没有完整私有归档，不能执行覆盖恢复"
        }
        if (mode == BackupImportMode.MERGE) backupService.createSafetySnapshot()
        update(CloudSyncPhase.MERGING, "正在恢复云快照")
        val manualOverlay=decoded.objects[CloudPrivateOverlay.NAME]?.let(CloudPrivateOverlay::decode).orEmpty()
        for(entry in manualOverlay.filter{it.kind=="article"&&it.objectName!=null})backupService.validatePrivateOverlayArticle(
            decoded.objects[entry.objectName] ?: throw java.io.IOException("私有覆盖正文暂不可用"),entry.id)
        val privateResult = privateArchive?.let {
            decodeCloudObject(snapshotId,PRIVATE_LIBRARY_OBJECT) { backupService.restorePrivateLibrary(it, mode,excludedCloudArticles=manualOverlay.filter{e->e.kind=="article"}.map{e->e.id}.toSet(),excludedCloudBooks=manualOverlay.filter{e->e.kind=="book"}.map{e->e.id}.toSet()) { checkCloudEpoch(session,expectedEpoch) } }
        }
        _restoreWarnings.value=privateResult?.warnings.orEmpty()
        for(entry in manualOverlay.filter{it.kind=="article"&&it.objectName!=null}) {
            val result=backupService.restorePrivateLibrary(decoded.objects.getValue(requireNotNull(entry.objectName)),BackupImportMode.MERGE){checkCloudEpoch(session,expectedEpoch)}
            _restoreWarnings.value+=result.warnings
        }
        if (privateArchive != null && mode == BackupImportMode.REPLACE) {
            recordStore.clear(client.scopeId(session))
            readRecordsForRestore(session, expectedEpoch)
        }
        backupService.applyRssInteractions(recordStore.rssStates(client.scopeId(session))) { checkCloudEpoch(session, expectedEpoch) }
        decoded.manifest.objects.filter { it.name in decoded.objects }.filter { it.name.startsWith("reader/") && it.privateArticle != null }.forEach { descriptor ->
            restoreReaderObject(session,expectedEpoch,snapshotId,descriptor,decoded.objects.getValue(descriptor.name))?.let { _restoreWarnings.value += it }
        }
        decoded.manifest.objects.filter{it.name in decoded.objects&&it.privateBook!=null}.forEach{descriptor->
            require(descriptor.name==requireNotNull(descriptor.privateBook).getString("packagePath"))
            backupService.restorePrivateBook(requireNotNull(descriptor.privateBook),decoded.objects.getValue(descriptor.name)){checkCloudEpoch(session,expectedEpoch)}
        }
        backupService.applyRssInteractions(recordStore.rssStates(client.scopeId(session))) { checkCloudEpoch(session, expectedEpoch) }
        val relayChanged = decoded.objects[RELAY_LIBRARY_OBJECT]
            ?.let { backupService.restoreCloudRelayLibrary(it) }
            ?: 0
        val stateResult = decoded.objects[RSS_STATE_OBJECT]
            ?.let { backupService.applyCloudRssState(it, restoreAsNewChange = true) }
            ?: (0 to 0)
        val settled=settleCloudRestoreWarnings(settings,snapshotId,_restoreWarnings.value) { checkCloudEpoch(session,expectedEpoch) }
        _restoreWarnings.value=settled.visible
        if (settled.pending.isEmpty() && stateResult.second==0) {
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
        }
        update(
            if (settled.pending.isEmpty() && stateResult.second==0) CloudSyncPhase.COMPLETE else CloudSyncPhase.IDLE,
            if (settled.pending.isNotEmpty()) "已恢复可用资料；${settled.pending.size} 项正文待重试" else if (_restoreWarnings.value.any { it.permanent }) "已恢复可用资料；${_restoreWarnings.value.count { it.permanent }} 项损坏资料已跳过" else if (mode == BackupImportMode.REPLACE) {
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
            rssStatesPending = stateResult.second+_restoreWarnings.value.size
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
                    pairingEpochState.value = null
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
        val expectedEpoch=client.epoch(session)
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
                decodeCloudObject(head.id,"manifest") { codec.decryptManifest(key, head.id, encrypted) }.also {
                    validateCloudManifestHead(it, head)
                    checkApprovalOwner(session)
                    synchronized(readingManifests) { readingManifests[cacheKey] = it }
                }
            }
            if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG && foreground) android.util.Log.d("WatchRssBody", "manifest projection=${head.sourceDeviceId.startsWith("reader-migration-")} total=${manifest.objects.size} versions=${manifest.objects.count { it.name.startsWith("reader/$hash/") }} exact=${manifest.objects.any { it.name == name }}")
            val descriptor = manifest.objects.firstOrNull { it.name == name } ?: continue
            if (cloudObjectDiagnosticId(head.id,descriptor.name) in settings.abandonedRestoreIds(head.id)) continue
            if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG && foreground) android.util.Log.d("WatchRssBody", "matched chapter chunks=${descriptor.chunks.size} bytes=${descriptor.originalBytes}")
            require(descriptor.originalBytes in 0..16L * 1024 * 1024 && descriptor.chunks.size <= 4096) { "章节过大，请稍后通过完整同步恢复" }
            val chunks = mutableMapOf<String, ByteArray>()
            for (chunk in manifest.chunksFor(descriptor)) {
                if (!foreground) yieldToReading()
                checkApprovalOwner(session)
                require(chunk.ciphertextBytes in 1..(17 * 1024 * 1024))
                val encrypted = cache.loadChunk(client.scopeId(session), chunk.ciphertextSha256) ?: run {
                    val remote = client.snapshot(session, head.id, chunk = chunk.ciphertextSha256)
                        .chunks.firstOrNull { it.sha256 == chunk.ciphertextSha256 }
                        ?: throw java.io.IOException("云对象块列表暂不完整，请稍后重试")
                    require(remote.sizeBytes == chunk.ciphertextBytes.toLong())
                    client.download(remote.signedUrl, remote.sizeBytes, remote.sha256)
                }
                chunks[chunk.ciphertextSha256] = encrypted
            }
            val text = try { decodeCloudObject(head.id,descriptor.name) {
                codec.restoreObjects(key, manifest.objectSubset(listOf(descriptor))) { chunks.getValue(it) }
                    .getValue(name).toString(Charsets.UTF_8)
            } } catch (error: Exception) {
                checkCloudEpoch(session,expectedEpoch)
                if (!permanentCloudRestoreFailure(error) || !recoverablePrivateFailure(error)) throw error
                val settled=settleCloudRestoreWarnings(settings,head.id,listOf(cloudPrivateObjectWarning(head.id,descriptor.name,error))) { checkCloudEpoch(session,expectedEpoch) }
                _restoreWarnings.value += settled.visible
                continue
            }
            checkApprovalOwner(session)
            if (article.contentText.isNotEmpty()) {
                val whitespace = Regex("\\s+")
                require(article.contentText.replace(whitespace, "") == text.replace(whitespace, "")) { "正文版本不一致" }
            }
            repository.fillMissingCloudBody(article, text) { checkApprovalOwner(session) }
            return
        }
        throw MissingCloudBodyException()
    }

    private val bluetoothFence=BluetoothAutomaticCloudFence()
    fun setBluetoothSyncActive(active:Boolean) {bluetoothFence.setActive(active)}
    val hasDeferredBluetoothSync:Boolean get()=bluetoothFence.needsResume

    suspend fun syncNow(manual: Boolean = false): PhoneCloudSyncResult = withContext(Dispatchers.IO) {
        if(!manual && bluetoothFence.active)return@withContext PhoneCloudSyncResult(0,0,0,false,0)
        val activeSession=accountRepository.session.value
        if(activeSession!=null)bindScope(activeSession)
        if(!manual && settings.formatUpgradeRequiredVersion>=com.lightningstudio.watchrss.phone.BuildConfig.VERSION_CODE) {
            _state.update{it.copy(phase=CloudSyncPhase.ERROR,message="请升级到最新版本以继续同步",errorCode="cloud_format_upgrade_required")}
            return@withContext PhoneCloudSyncResult(0,0,0,false,0)
        }
        traceSync("sync-request")
        syncMutex.run(manual, { PhoneCloudSyncResult(0, 0, 0, false, 0) }) {
            val automaticJob=if(manual)null else kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            if(!manual && bluetoothFence.active)return@run PhoneCloudSyncResult(0,0,0,false,0)
            bluetoothFence.started(manual,automaticJob)
            var retryingTransfer = false
            try {
                if(!manual && bluetoothFence.active)return@run PhoneCloudSyncResult(0,0,0,false,0)
                retryDiscardedCloudTransfer(rebuild = { failure ->
                    val session = requireSession()
                    if (failure.status == 409) clearContentSyncState(session)
                    // A current-generation expired plan keeps authenticated immutable bytes for
                    // reuse only. The new reserve's missingObjects are the sole upload authority.
                    retryingTransfer = true
                    traceSync("transfer-rebuild:server-discarded")
                    update(CloudSyncPhase.CHECKING,"云端内容已清理，正在从本机重建同步")
                    client.bootstrap(session)
                }, attempt = {
                    if (paused.value || !networkGate.isConnected()) syncOnePass(manual, retryingTransfer)
                    else {
                        val session = requireSession()
                        client.bootstrap(session)
                        client.inContentGeneration(session) { syncOnePass(manual, retryingTransfer) }
                    }
                })
            } catch (error: Exception) {
                if (error is CancellationException) {
                    update(if (paused.value) CloudSyncPhase.PAUSED else CloudSyncPhase.IDLE, "等待继续同步")
                    throw error
                }
                val transientRebuildFailure = retryingTransfer && error is java.io.IOException &&
                    (error !is CloudHttpException || error.status in setOf(408,429,500,502,503,504))
                if (transientRebuildFailure || error is CloudHttpException && (discardedCloudTransfer(error) || cloudErrorCode(error) == "cloud_storage_cleanup_pending")) {
                    update(CloudSyncPhase.IDLE, "云端内容正在重建，已排队重试；本机资料已保留")
                    PhoneCloudSyncWorker.scheduleFollowUp(appContext)
                } else if (error is CloudDeviceAuthorizationException && _state.value.phase != CloudSyncPhase.NEEDS_RECOVERY) {
                    invalidateEpoch(requireSession())
                    update(CloudSyncPhase.NEEDS_RECOVERY, "设备身份需要重新验证")
                } else if (_state.value.phase !in setOf(CloudSyncPhase.NEEDS_RECOVERY,CloudSyncPhase.ERROR)) {
                    update(CloudSyncPhase.ERROR, error.message ?: "云同步失败")
                }
                if(error is CloudFormatUpgradeRequired || error is CloudHttpException && error.status==426) {
                    // Only the authenticated account floor halts same-build background passes.
                    // A locally read-only object must not freeze reads or unrelated records.
                    if(error is CloudHttpException)settings.formatUpgradeRequiredVersion=com.lightningstudio.watchrss.phone.BuildConfig.VERSION_CODE
                    _formatStatus.value=_formatStatus.value.copy(upgradeRequired=true)
                    _state.update{it.copy(phase=CloudSyncPhase.ERROR,message="请升级到最新版本以继续同步",errorCode="cloud_format_upgrade_required")}
                }
                throw error
            } finally { bluetoothFence.finished(automaticJob) }
        }
    }

    private suspend fun syncOnePass(manual: Boolean, retryingTransfer: Boolean = false): PhoneCloudSyncResult = run {
            traceSync("sync-lock")
            _restoreWarnings.value=emptyList(); _rssRefreshWarnings.value=emptyList()
            var uploadedBytes = 0L
            var downloadedBytes = 0L
            var applied = 0
            var pendingStates = 0
            var uploaded = false
            var snapshotDeferredUntil = 0L
            fun pausedResult(): PhoneCloudSyncResult? {
                if (!paused.value) return null
                update(CloudSyncPhase.PAUSED, "已暂停同步")
                return PhoneCloudSyncResult(uploadedBytes, downloadedBytes, applied, uploaded, pendingStates)
            }
            pausedResult()?.let { return@run it }
            try {
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
                settings.ensureRestoreFailureClassificationV2()
                settings.ensureFullBodyRestoreVersion(session.userId)
                settings.requireUploadOwner(session.userId)
                settings.ensureRssChannelOnlyVersion { recordStore.clear(client.scopeId(session)) }
                traceSync("records-pull:start")
                readRecordsForRestore(session, bootstrap.libraryEpoch)
                traceSync("records-pull:done")
                val mergeProgress = CloudSnapshotMergeProgress()
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
                    if (outcome.warningsOnly) mergeProgress.pendingWarnings(head)
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
                    traceSync("rss-refresh:start")
                    refreshRssInventory(session)
                    traceSync("rss-refresh:done")
                }

                pausedResult()?.let { return@run it }
                backupService.applyRssInteractions(recordStore.rssStates(client.scopeId(session))) { checkCloudEpoch(session, bootstrap.libraryEpoch) }
                val compacted = repository.compactCloudExcerpts { checkCloudEpoch(session, bootstrap.libraryEpoch) }
                traceSync("metadata-compacted:$compacted")
                if (fullTransfer) {
                    val missingBodies = repository.missingCloudBodyIds().filterNot { id -> _restoreWarnings.value.any { it.articleId==id } }
                    val bodyHeads = if (missingBodies.isEmpty()) emptyList() else try { readingHeads(session) }
                    catch (error: Exception) {
                        checkCloudEpoch(session, bootstrap.libraryEpoch)
                        if (!recoverablePrivateFailure(error)) throw error
                        // History is only a body lookup aid. Already authenticated current heads
                        // can still supply the body; otherwise each missing article gets its warning.
                        heads
                    }
                    for (articleId in missingBodies) {
                        pausedResult()?.let { return@run it }
                        yieldToReading()
                        val article = repository.getArticle(articleId) ?: continue
                        if (article.contentText.isNotBlank() || !article.contentHtml.isNullOrBlank()) continue
                        update(CloudSyncPhase.DOWNLOADING, "正在恢复文章正文", bootstrap.member)
                        restoreCloudBodyOrWarn(articleId, { checkCloudEpoch(session, bootstrap.libraryEpoch) }) {
                            restoreArticleBody(session, article, bodyHeads)
                        }?.let { _restoreWarnings.value += it }
                    }
                    val missingBooks=repository.books?.missingBookIds().orEmpty()
                    _restoreWarnings.value=_restoreWarnings.value.filterNot{it.reason=="missing_book_body"}+
                        missingBooks.map{CloudRestoreWarning(it,"missing_book_body")}
                    pendingStates=maxOf(pendingStates,_restoreWarnings.value.count { !it.permanent })
                }
                pausedResult()?.let { return@run it }
                traceSync("books-register:start")
                repository.books?.ensureRegistered()
                traceSync("books-register:done")
                // Compare against the publication window, after this pass's own restore/RSS work.
                val localSequenceAtPublish = repository.getLibrarySyncCursor(null).localMaxSeq
                traceSync("records-push:start")
                try { recordSync.sync(session, true, bootstrap.libraryEpoch, requireAvailable = true); _formatStatus.value=recordStore.formatStatus(client.scopeId(session)); _recordError.value = null }
                catch (e: Exception) { _recordError.value = "记录尚未同步，请稍后重试"; throw e }
                traceSync("records-push:done")
                run {
                    settings.requireUploadOwner(session.userId)
                    val currentKeyVersion = keyManager.currentKeyVersion(session.userId)
                    val accountKey = keyManager.getAccountKey(session.userId, currentKeyVersion)
                        ?: error("当前账号主密钥不存在")
                    val freshHeads = client.snapshotHeads(session)
                    checkCloudEpoch(session, bootstrap.libraryEpoch)
                    check(mergeProgress.readyToPublish(freshHeads) { settings.appliedSequence(it, fullTransfer) }) { "云端资料仍有更新待合并，将继续同步" }
                    val uploadResult = uploadSnapshot(
                        session = session,
                        accountKey = accountKey,
                        bootstrap = bootstrap,
                        heads = freshHeads,
                        mergedHeads = mergeProgress.appliedHeads(freshHeads) { settings.appliedSequence(it, fullTransfer) },
                        fullTransfer = fullTransfer,
                        keyVersion = currentKeyVersion,
                        manual = manual || retryingTransfer
                    )
                    uploadedBytes += uploadResult.bytes
                    uploaded = uploadResult.uploaded
                    snapshotDeferredUntil = uploadResult.deferredUntil
                    if (uploadResult.confirmedArticleIds.isNotEmpty() || uploadResult.confirmedBookIds.isNotEmpty()) recordSync.retireSavedRssIndexes(session,bootstrap.libraryEpoch,uploadResult.confirmedArticleIds,uploadResult.confirmedBookIds)
                }
                bootstrap = try { client.bootstrap(session) } catch (error: java.io.IOException) {
                    if (error is CloudHttpException && error.status !in setOf(408,429,500,502,503,504)) throw error
                    bootstrap
                }
                checkCloudEpoch(session,syncEpoch)
                _rssRefreshWarnings.value=(_rssRefreshWarnings.value + backupService.unresolvedRssStateUrls).distinct()
                if (pendingStates>0) {
                    update(CloudSyncPhase.IDLE,"已恢复可用资料；${pendingStates} 项正文待重试",bootstrap.member,uploadedBytes,downloadedBytes)
                } else if (!fullTransfer) {
                    update(
                        CloudSyncPhase.WAITING_FOR_WIFI,
                        "小状态已同步；正文等待Wi‑Fi",
                        bootstrap.member,
                        uploadedBytes,
                        downloadedBytes
                    )
                } else if (snapshotDeferredUntil > 0) {
                    update(CloudSyncPhase.IDLE, "阅读状态已同步；私有快照等待合并窗口", bootstrap.member, uploadedBytes, downloadedBytes)
                    _state.update { it.copy(pendingSnapshotUntil=snapshotDeferredUntil) }
                } else if (localSequenceAtPublish != repository.getLibrarySyncCursor(null).localMaxSeq) {
                    val completion = cloudPassCompletion(true)
                    update(completion.first, completion.second, bootstrap.member, uploadedBytes, downloadedBytes)
                    PhoneCloudSyncWorker.scheduleFollowUp(appContext)
                    traceSync("follow-up:scheduled")
                } else {
                    update(
                        cloudPassCompletion(false).first,
                        if (_restoreWarnings.value.any { it.permanent }) "云同步完成；${_restoreWarnings.value.count { it.permanent }} 项损坏资料已跳过" else if (_rssRefreshWarnings.value.isEmpty()) "云同步完成" else "资料已同步；${_rssRefreshWarnings.value.size} 个频道暂未入池或刷新失败",
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
                if(error is CloudFormatUpgradeRequired || error is CloudHttpException && error.status==426)_formatStatus.value=_formatStatus.value.copy(upgradeRequired=true)
                traceSync("failure:${error.javaClass.simpleName}:status=${(error as? CloudHttpException)?.status ?: (error as? CloudObjectUploadException)?.status}:${error.stackTrace.firstOrNull { it.className.startsWith("com.lightningstudio.watchrss.phone") }}")
                if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG) android.util.Log.w("WatchRssCloud", "sync failed phase=${_state.value.phase} type=${error.javaClass.simpleName} at=${error.stackTrace.firstOrNull { it.className.startsWith("com.lightningstudio.watchrss.phone") }}" + if (error is CloudHttpException) " status=${error.status}" else "")
                if (error is CloudHttpException && discardedCloudTransfer(error)) throw error
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

    private suspend fun readRecordsForRestore(session: PhoneAccountSession, epoch: String) {
        val available = readCloudRecords({ checkCloudEpoch(session, epoch) }) {
            recordSync.sync(session, false, epoch, requireAvailable = true)
        }
        _formatStatus.value=recordStore.formatStatus(client.scopeId(session))
        _recordError.value = if (available) null else "记录通道暂不可用；可用快照仍会恢复，请稍后重试"
    }

    private suspend fun refreshRssInventory(session: PhoneAccountSession) {
        val failed=refreshCloudRssChannels(repository.getCloudEligibleRssSources(),{ paused.value },{ checkApprovalOwner(session) }) { source ->
            val inventory=client.rssInventory(session,source.url,rssInventoryPreferences.mode(source.url))
            checkApprovalOwner(session);repository.mergeCloudRssInventory(inventory)
        }
        _rssRefreshWarnings.value=(_rssRefreshWarnings.value+failed).distinct()
    }

    private fun recoverablePrivateFailure(error: Exception): Boolean =
        recoverableCloudRestoreFailure(error)

    private suspend fun restoreReaderObject(session: PhoneAccountSession, epoch: String, snapshotId: String, descriptor: CloudObjectDescriptor, bytes: ByteArray): CloudRestoreWarning? =
        try {
            decodeCloudObject(snapshotId,descriptor.name) {
                validatePrivateReaderArticle(descriptor)
                backupService.restorePrivateReaderArticle(requireNotNull(descriptor.privateArticle),bytes.toString(Charsets.UTF_8)) { checkCloudEpoch(session,epoch) }
            }
        } catch (error: Exception) {
            checkCloudEpoch(session,epoch)
            if (!recoverablePrivateFailure(error)) throw error
            cloudPrivateObjectWarning(snapshotId,descriptor.name,error)
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
        val manifest = decodeCloudObject(head.id,"manifest") { codec.decryptManifest(accountKey, head.id, encryptedManifest) }.also { validateCloudManifestHead(it, head);observeFormat(session,it) }
        cache.recordManifestReferences(client.scopeId(session),
            head.id,
            manifest.allChunks.map(CloudChunkDescriptor::ciphertextSha256)
        )
        val durableOverlay=if(fullTransfer)recordStore.privateOverlayEntries(client.scopeId(session))else emptyList()
        val overlayArticles=durableOverlay.filter{it.kind=="article"}.mapTo(mutableSetOf()){it.id}
        val overlayBooks=durableOverlay.filter{it.kind=="book"}.mapTo(mutableSetOf()){it.id}
        val appliedOverlayEntries=mutableMapOf<String,CloudPrivateOverlayEntry>()
        val overlayBodies=linkedMapOf<String,ByteArray>()
        var overlayPending=0
        if(fullTransfer && manifest.objects.any{it.name==CloudPrivateOverlay.NAME}) {
            require(manifest.rawDocument?.json()?.optJSONObject("features")?.optJSONArray("incompat")?.let{a->(0 until a.length()).any{a.getString(it)==CloudPrivateOverlay.FEATURE}}==true)
            val index=manifest.objects.single{it.name==CloudPrivateOverlay.NAME}
            val entries=CloudPrivateOverlay.decode(materializeObject(session,accountKey,manifest,index,listOf(head)))
            for(entry in entries) {
                val marker="private-overlay:"+entry.kind+":"+CloudSnapshotCodec.sha256(entry.id.toByteArray())
                val old=recordStore.document(client.scopeId(session),marker)?.let{raw->CloudPrivateOverlay.decode(("{\"format\":\"watchrss-private-overlay\",\"version\":1,\"entries\":["+raw+"]}").toByteArray()).single()}
                val winner=CloudPrivateOverlay.merge(listOfNotNull(old,entry)).single()
                if(winner!=entry) {
                    if(entry.kind=="article")overlayArticles+=entry.id else overlayBooks+=entry.id
                    continue
                }
                if(entry.objectName==null) {
                    if(entry.kind=="article")overlayArticles+=entry.id else overlayBooks+=entry.id
                    recordStore.saveDocument(client.scopeId(session),marker,entry.changedAt.toString(),entry.json().toString(),CloudFormatAccess.WRITABLE)
                    continue
                }
                val descriptor=manifest.objects.singleOrNull{it.name==entry.objectName}
                if(descriptor==null || descriptor.access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED)) {overlayPending++;continue}
                try {
                    val bodyMarker="private-overlay-body:"+entry.kind+":"+CloudSnapshotCodec.sha256(entry.id.toByteArray())
                    val oldDescriptor=recordStore.document(client.scopeId(session),bodyMarker)
                    if(old==entry && oldDescriptor!=null && oldDescriptor!=descriptor.toRawString())throw CloudFormatUpgradeRequired("overlay_body_conflict")
                    val bytes=materializeObject(session,accountKey,manifest,descriptor,listOf(head))
                    if(entry.kind=="article") {
                        backupService.validatePrivateOverlayArticle(bytes,entry.id)
                        overlayBodies[entry.id]=bytes;overlayArticles+=entry.id;appliedOverlayEntries[entry.id]=entry
                    } else {
                        require(descriptor.privateBook?.getString("bookId")==entry.id)
                        overlayBooks+=entry.id;appliedOverlayEntries[entry.id]=entry
                    }
                } catch(error:Exception) {
                    checkCloudEpoch(session,expectedEpoch)
                    if(error !is CloudFormatUpgradeRequired && !recoverableCloudRestoreFailure(error))throw error
                    overlayPending++
                }
            }
        }
        val formatPending=manifest.objects.count { it.access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED) &&
            (it.name in setOf(RSS_STATE_OBJECT,NOTES_STATE_OBJECT,NOTES_ARCHIVE_OBJECT) || fullTransfer && (it.name in setOf(PRIVATE_LIBRARY_OBJECT,RELAY_LIBRARY_OBJECT)||it.privateArticle!=null||it.privateBook!=null)) }
        val selectedObjects = manifest.objects.filter { it.access !in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED) }.filter { descriptor ->
            descriptor.name == RSS_STATE_OBJECT || descriptor.name == NOTES_STATE_OBJECT || descriptor.name == NOTES_ARCHIVE_OBJECT ||
                (fullTransfer && (descriptor.name in setOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT) || (descriptor.name.startsWith("reader/") && descriptor.privateArticle != null) || descriptor.privateBook!=null))
        }
        val suppressedBooks=recordStore.privateOverlayEntries(client.scopeId(session)).filter{it.kind=="book" && it.objectName==null}.map{it.id}.toSet()
        val chunkUrls = download.chunks.associateBy(CloudDownloadObject::sha256)
        yieldToReading()
        checkCloudEpoch(session, expectedEpoch)
        update(CloudSyncPhase.MERGING, "正在合并远端快照")
        var changed = false
        var rssPending = overlayPending
        val privateWarnings=mutableListOf<CloudRestoreWarning>()
        // Restore one logical object at a time: retaining the archive, relay and
        // RSS JSON together can exhaust the heap on a full phone library.
        val restoreOrder = listOf(PRIVATE_LIBRARY_OBJECT, RELAY_LIBRARY_OBJECT, RSS_STATE_OBJECT, NOTES_STATE_OBJECT, NOTES_ARCHIVE_OBJECT)
        for (descriptor in selectedObjects.sortedBy { restoreOrder.indexOf(it.name) }) {
            if(descriptor.privateBook?.optString("bookId") in suppressedBooks)continue
            yieldToReading()
            checkCloudEpoch(session, expectedEpoch)
            traceSync("object-start:${descriptor.name}")
            val isPrivate=descriptor.name==PRIVATE_LIBRARY_OBJECT || descriptor.privateArticle!=null || descriptor.privateBook!=null
            if (isPrivate && cloudObjectDiagnosticId(head.id,descriptor.name) in settings.abandonedRestoreIds(head.id)) continue
            try {
            for (chunk in manifest.chunksFor(descriptor).distinctBy { it.ciphertextSha256 }) {
                checkCloudEpoch(session,expectedEpoch);yieldToReading()
                if (cache.loadChunk(client.scopeId(session),chunk.ciphertextSha256)==null) {
                    val remote=chunkUrls[chunk.ciphertextSha256] ?: throw java.io.IOException("云对象块列表暂不完整，请稍后重试")
                    val bytes=client.download(remote.signedUrl,remote.sizeBytes,remote.sha256)
                    checkCloudEpoch(session,expectedEpoch);cache.storeChunk(client.scopeId(session),remote.sha256,bytes);downloadedBytes+=bytes.size
                }
            }
            val bytes = decodeCloudObject(head.id,descriptor.name) {
                codec.restoreObjects(accountKey, manifest.objectSubset(listOf(descriptor))) {
                    sha -> cache.loadChunk(client.scopeId(session), sha) ?: throw java.io.IOException("本地加密块暂不可用，请稍后重试")
                }.getValue(descriptor.name)
            }
            if(descriptor.privateBook!=null) {
                val value=requireNotNull(descriptor.privateBook)
                require(descriptor.name==value.getString("packagePath"))
                decodeCloudObject(head.id,descriptor.name) {backupService.restorePrivateBook(value,bytes){checkCloudEpoch(session,expectedEpoch)}}
                changed=true
                appliedOverlayEntries[value.getString("bookId")]?.let{entry->recordStore.saveDocument(client.scopeId(session),"private-overlay:book:"+CloudSnapshotCodec.sha256(entry.id.toByteArray()),entry.changedAt.toString(),entry.json().toString(),CloudFormatAccess.WRITABLE)}
            }
            if (descriptor.name.startsWith("reader/") && descriptor.privateArticle != null) {
                restoreReaderObject(session,expectedEpoch,head.id,descriptor,bytes)?.let(privateWarnings::add)
                changed = true
            }
            when (descriptor.name) {
                PRIVATE_LIBRARY_OBJECT -> {
                    val result = decodeCloudObject(head.id,descriptor.name) {
                        backupService.restorePrivateLibrary(bytes, BackupImportMode.MERGE,
                            excludedCloudArticles=overlayArticles,excludedCloudBooks=overlayBooks) { checkCloudEpoch(session, expectedEpoch) }
                    }
                    privateWarnings += result.warnings
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
            } catch (error: Exception) {
                checkCloudEpoch(session,expectedEpoch)
                if (!isPrivate || !recoverablePrivateFailure(error)) throw error
                privateWarnings += cloudPrivateObjectWarning(head.id,descriptor.name,error)
            }
        }
        for((id,bytes) in overlayBodies) {
            checkCloudEpoch(session,expectedEpoch)
            val applied=backupService.restorePrivateLibrary(bytes,BackupImportMode.MERGE){checkCloudEpoch(session,expectedEpoch)}
            privateWarnings+=applied.warnings
            changed=changed || applied.changedArticleCount>0
            if(applied.warnings.isEmpty()) {
                val entry=appliedOverlayEntries.getValue(id)
                recordStore.saveDocument(client.scopeId(session),"private-overlay:article:"+CloudSnapshotCodec.sha256(id.toByteArray()),entry.changedAt.toString(),entry.json().toString(),CloudFormatAccess.WRITABLE)
                recordStore.saveDocument(client.scopeId(session),"private-overlay-body:article:"+CloudSnapshotCodec.sha256(id.toByteArray()),entry.changedAt.toString(),manifest.objects.single{it.name==entry.objectName}.toRawString(),CloudFormatAccess.WRITABLE)
            }
        }
        val settled=settleCloudRestoreWarnings(settings,head.id,privateWarnings) { checkCloudEpoch(session,expectedEpoch) }
        _restoreWarnings.value += settled.visible
        cache.storeManifest(client.scopeId(session),
            head.id,
            encryptedManifest,
            markAsLocalHead = false
        )
        return PullOutcome(
            downloadedBytes = downloadedBytes,
            pendingStates = rssPending+settled.pending.size+formatPending,
            changed = changed,
            stateApplied = rssPending == 0 && settled.pending.isEmpty() && formatPending == 0,
            complete = fullTransfer && rssPending == 0 && settled.pending.isEmpty() && formatPending == 0,
            warningsOnly = rssPending == 0 && (settled.pending.isNotEmpty() || formatPending > 0)
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
        val manifest = decodeCloudObject(snapshotId,"manifest") { codec.decryptManifest(accountKey, snapshotId, encryptedManifest) }.also { validateCloudManifestHead(it, download.head);observeFormat(session,it) }
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
        val objects = manifest.objects.filter{it.access !in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED)}.associate { descriptor -> descriptor.name to decodeCloudObject(snapshotId,descriptor.name) {
            codec.restoreObjects(accountKey, manifest.objectSubset(listOf(descriptor))) { sha ->
                cache.loadChunk(client.scopeId(session), sha) ?: throw java.io.IOException("本地加密块暂不可用，请稍后重试")
            }.getValue(descriptor.name)
        } }
        return DecodedSnapshotObjects(download, objects, manifest)
    }

    private suspend fun authenticatedObjectHashes(session:PhoneAccountSession,key:ByteArray,manifests:List<CloudSnapshotManifest>,heads:List<CloudSnapshotHead>,objects:List<CloudLogicalObject>):Map<String,String> {
        val scope=client.scopeId(session);val remotes=mutableMapOf<String,CloudSnapshotDownload>()
        val result=linkedMapOf<String,String>()
        for(logical in objects) {
            val manifest=manifests.firstOrNull{m->m.objects.any{it.name==logical.name}}?:continue
            val d=manifest.objects.single{it.name==logical.name}
            if(d.access in setOf(CloudFormatAccess.OPAQUE,CloudFormatAccess.ISOLATED))continue
            val plain=logical.sha256()
            if(d.accountRoot&&d.encoding=="identity"&&d.chunks.size==1&&d.chunks.single().plaintextSha256==plain) {result[d.name]=plain;continue}
            for(chunk in manifest.chunksFor(d))if(cache.loadChunk(scope,chunk.ciphertextSha256)==null) {
                val download=remotes[manifest.snapshotId]?:client.snapshot(session,manifest.snapshotId).also {r->
                    validateCloudManifestHeadIdentity(heads.single{it.id==manifest.snapshotId},r.head);remotes[manifest.snapshotId]=r}
                val remote=download.chunks.singleOrNull{it.sha256==chunk.ciphertextSha256}?:throw java.io.IOException("原对象块暂不可用")
                cache.storeChunk(scope,chunk.ciphertextSha256,client.download(remote.signedUrl,remote.sizeBytes,remote.sha256))
            }
            val bytes=codec.restoreObjects(key,manifest.objectSubset(listOf(d))){sha->cache.loadChunk(scope,sha)?:throw java.io.IOException("原对象块暂不可用")}.getValue(d.name)
            result[d.name]=CloudSnapshotCodec.sha256(bytes)
            if(logical.name==PRIVATE_LIBRARY_OBJECT&&result[d.name]!=plain&&logical.file!=null) {
                com.lightningstudio.watchrss.phone.data.backup.WatchRssBackupArchive.preserveUnknownEntries(bytes,logical.file)
            }
        }
        return result
    }

    private suspend fun materializeObject(session:PhoneAccountSession,key:ByteArray,manifest:CloudSnapshotManifest,descriptor:CloudObjectDescriptor,heads:List<CloudSnapshotHead>):ByteArray {
        val scope=client.scopeId(session)
        var remote:CloudSnapshotDownload?=null
        for(chunk in manifest.chunksFor(descriptor))if(cache.loadChunk(scope,chunk.ciphertextSha256)==null) {
            checkCloudEpoch(session,manifest.settings.getString("libraryEpoch"))
            val download=remote ?: client.snapshot(session,manifest.snapshotId).also {
                validateCloudManifestHeadIdentity(heads.single{h->h.id==manifest.snapshotId},it.head);remote=it
            }
            val ref=download.chunks.singleOrNull{it.sha256==chunk.ciphertextSha256} ?: throw java.io.IOException("云对象块暂不可用")
            cache.storeChunk(scope,chunk.ciphertextSha256,client.download(ref.signedUrl,ref.sizeBytes,ref.sha256))
        }
        checkCloudEpoch(session,manifest.settings.getString("libraryEpoch"))
        return codec.restoreObjects(key,manifest.objectSubset(listOf(descriptor))){sha->cache.loadChunk(scope,sha) ?: throw java.io.IOException("云对象块暂不可用")}.getValue(descriptor.name)
    }

    private suspend fun uploadSnapshot(
        session: PhoneAccountSession,
        accountKey: ByteArray,
        bootstrap: CloudBootstrap,
        heads: List<CloudSnapshotHead>,
        mergedHeads: List<CloudSnapshotHead>,
        fullTransfer: Boolean,
        keyVersion: Int,
        manual: Boolean
    ): UploadOutcome {
        backupService.requireArticleProvenanceWritable()
        settings.requireUploadOwner(session.userId)
        verifyLocalKeys(session, requireNotNull(trustedBootstrap))
        val scope = client.scopeId(session)
        recordStore.markHeads(scope,heads.mapTo(hashSetOf()){it.id})
        val currentManifests = mutableListOf<CloudSnapshotManifest>()
        for (head in heads.filter { it.keyVersion == keyVersion && !it.sourceDeviceId.startsWith("reader-migration-") }) {
            checkCloudEpoch(session, bootstrap.libraryEpoch)
            val bytes = cache.loadManifest(scope, head.id) ?: run {
                val remote = client.snapshot(session, head.id, manifestOnly = true)
                validateCloudManifestHeadIdentity(head, remote.head)
                client.download(remote.manifestSignedUrl, head.manifestSizeBytes, head.manifestSha256).also {
                    checkCloudEpoch(session, bootstrap.libraryEpoch)
                    cache.storeManifest(scope,head.id,it,markAsLocalHead=false)
                }
            }
            currentManifests += decodeCloudObject(head.id,"manifest") { CloudSnapshotReuse.authenticate(codec,accountKey,head,bytes) }.also {
                observeFormat(session,it)
            }
        }
        val previous = currentManifests.firstOrNull { it.sourceDeviceId == deviceId }
            ?: currentManifests.firstOrNull()
        val privateFingerprint = if (fullTransfer) backupService.privateCloudFingerprint() else previous?.settings?.optString("privateBodyFingerprintV2")?.takeIf { it.isNotBlank() }
        val pendingManifest = cache.loadPendingManifest(scope)?.let { (id, bytes) ->
            runCatching { codec.decryptManifest(accountKey,id,bytes) }.getOrNull()?.takeIf {
                it.keyVersion == keyVersion && it.sourceDeviceId == deviceId && it.settings.optString("libraryEpoch") == bootstrap.libraryEpoch &&
                    it.settings.optString("ownerUserId") == session.userId && it.allChunks.all { chunk -> cache.loadChunk(scope,chunk.ciphertextSha256) != null }
            }
        }
        val carryScope=JSONObject().put("backendScope",scope).put("contentGeneration",client.knownContentGeneration(session)).put("ownerUserId",session.userId).put("libraryEpoch",bootstrap.libraryEpoch)
        val writeOverlay = formatWritePolicy.localWrite && formatWritePolicy.canWrite(client.formatCapabilities(session))
        val bodySource = (currentManifests + listOfNotNull(pendingManifest)).firstOrNull {
            CloudSnapshotReuse.scopeCompatible(it,carryScope,allowMissing=true) && (CloudSnapshotReuse.canCarry(it, session.userId, bootstrap.libraryEpoch, keyVersion, privateFingerprint) || writeOverlay && it.objects.any{d->d.name==PRIVATE_LIBRARY_OBJECT})
        }
        if(fullTransfer && !writeOverlay && currentManifests.any{it.objects.any{d->d.name==CloudPrivateOverlay.NAME}} &&
            currentManifests.none{it.settings.optString("privateBodyFingerprintV2")==privateFingerprint}) {
            pendingFormat(session,"manifest:private-overlay-write-disabled")
            throw CloudFormatUpgradeRequired("overlay_write_disabled")
        }
        val priorBodies = bodySource?.objects.orEmpty().filter { it.name == PRIVATE_LIBRARY_OBJECT }
        if (!fullTransfer && priorBodies.isEmpty()) return UploadOutcome(false,0)
        traceSync("private-archive:start")
        val createdArchive=if (priorBodies.isEmpty() && fullTransfer) backupService.createCloudPrivateArchiveExport() else null
        try {
        traceSync("private-archive:done:bytes=${createdArchive?.file?.length() ?: 0}:carried=${priorBodies.size}")
        val overlayRows=mutableListOf<Pair<CloudSnapshotManifest,CloudPrivateOverlayEntry>>()
        if(writeOverlay && fullTransfer)for(manifest in currentManifests) {
            val index=manifest.objects.singleOrNull{it.name==CloudPrivateOverlay.NAME} ?: continue
            val bytes=materializeObject(session,accountKey,manifest,index,heads)
            CloudPrivateOverlay.decode(bytes).forEach{overlayRows+=manifest to it}
        }
        val oldOverlay=CloudPrivateOverlay.merge(overlayRows.map{it.second})
        val winningObjects=oldOverlay.mapNotNull { entry ->
            val name=entry.objectName ?: return@mapNotNull null
            val ties=overlayRows.filter{it.second==entry}
            val descriptors=ties.map{(m,_)->m.objects.singleOrNull{it.name==name} ?: throw java.io.IOException("私有覆盖对象暂不可用")}
            if(descriptors.map{it.toRawString()}.distinct().size!=1)throw CloudFormatUpgradeRequired("overlay_body_conflict")
            val source=ties.first().first
            name to (source to (source.objects.singleOrNull{it.name==name} ?: throw java.io.IOException("私有覆盖对象暂不可用")))
        }.toMap()
        val overlayExport=if(writeOverlay && fullTransfer && createdArchive==null && bodySource!=null && privateFingerprint!=bodySource.settings.optString("privateBodyFingerprintV2")) {
            val backing=materializeObject(session,accountKey,bodySource,priorBodies.single(),heads)
            val oldBytes=winningObjects.filterKeys{it.startsWith("private/articles/")}.mapValues{(_,pair)->materializeObject(session,accountKey,pair.first,pair.second,heads)}
            try {backupService.createPrivateOverlayExport(backing,oldOverlay,oldBytes)}
            catch(error:CloudFormatUpgradeRequired) {pendingFormat(session,"manifest:private-overlay-pending");throw error}
        } else null
        val notes = noteRepository.allNotes()
        val logicalObjects = buildList {
            createdArchive?.let { add(CloudLogicalObject(PRIVATE_LIBRARY_OBJECT,it.file,false)) }
            overlayExport?.let { value ->
                addAll(value.objects)
                add(CloudLogicalObject(CloudPrivateOverlay.NAME,CloudPrivateOverlay.encode(value.entries)))
            }
            add(CloudLogicalObject(NOTES_STATE_OBJECT, NoteCloudStateCodec.encode(notes)))
            if (notes.any { !it.deleted }) add(CloudLogicalObject(NOTES_ARCHIVE_OBJECT, noteTransfer.exportZip()))
        }
        val overlayCarried=if(overlayExport!=null) {
            val live=overlayExport.entries.mapNotNull{it.objectName}.toSet()
            winningObjects.values.map{it.second}.filter{it.name in live && it.name !in logicalObjects.map{l->l.name}}
        } else emptyList()
        val replaced=logicalObjects.map{it.name}.toSet()+if(overlayExport!=null)currentManifests.flatMap{it.objects}.filter{it.name.startsWith("private/articles/")||it.name.startsWith("books/")||it.name==CloudPrivateOverlay.NAME}.map{it.name} else emptyList()
        val carried = CloudSnapshotReuse.preserveObjects(currentManifests,replaced,priorBodies+overlayCarried)
        if(logicalObjects.size+carried.size>1024) {
            pendingFormat(session,"manifest:private-overlay-capacity")
            throw CloudFormatUpgradeRequired("manifest_object_limit")
        }
        val parentHeads = mergedHeads.associate { it.sourceDeviceId to it.id }
        val observedHeads = mergedHeads.associate { it.sourceDeviceId to it.deviceSequence }
        val contentHash = CloudSnapshotContent.hash(logicalObjects, carried, keyVersion, privateFingerprint)
        val own = currentManifests.firstOrNull { it.sourceDeviceId == deviceId }
        val contentChanged = (own?.settings?.optString("snapshotContentHashV3") ?: settings.lastContentHash(fullTransfer)) != contentHash || own == null
        val lastPublished = maxOf(settings.lastSnapshotPublishedAt,
            heads.filter { it.sourceDeviceId == deviceId }.mapNotNull { head -> head.completedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } }.maxOrNull() ?: 0)
        if (!CloudContentPolicy.shouldPublish(contentChanged, manual, lastPublished, System.currentTimeMillis())) {
            val due = if (contentChanged && !manual) lastPublished + CloudContentPolicy.MIN_SNAPSHOT_INTERVAL_MS else 0L
            createdArchive?.file?.delete()
            if (due > 0) PhoneCloudSyncWorker.scheduleSnapshot(appContext,due-System.currentTimeMillis())
            return UploadOutcome(false,0,confirmedArticleIds=if (fullTransfer) CloudSnapshotReuse.completedArticleIds(currentManifests,session.userId,bootstrap.libraryEpoch,keyVersion,privateFingerprint) else emptySet(),deferredUntil=due,confirmedBookIds=if(fullTransfer) CloudSnapshotReuse.completedBookIds(currentManifests,session.userId,bootstrap.libraryEpoch,keyVersion,privateFingerprint) else emptySet())
        }
        for(manifest in currentManifests)if(manifest.access!=CloudFormatAccess.WRITABLE) {
            pendingFormat(session,"manifest:${manifest.snapshotId}");manifest.requireWritable()
        }
        val authenticatedHashes=authenticatedObjectHashes(session,accountKey,currentManifests,heads,logicalObjects)
        for(old in currentManifests.flatMap{it.objects}.filter { it.name in logicalObjects.map{logical->logical.name} }) {
            if(old.access!=CloudFormatAccess.WRITABLE && authenticatedHashes[old.name]!=logicalObjects.first{it.name==old.name}.sha256()) {
                pendingFormat(session,"object:${old.name}");throw CloudFormatUpgradeRequired("object_read_only")
            }
        }
        val serverSequence = bootstrap.devices
            .firstOrNull { it.deviceId == deviceId }
            ?.lastSequence
            ?: 0L
        val reusableManifests = currentManifests
        val bodyIds=overlayExport?.articleIds ?: createdArchive?.articleIds ?: bodySource?.settings?.optJSONArray("privateBodyArticleIds")?.let { ids ->
            (0 until ids.length()).map { ids.getString(it) }.toSet()
        }.orEmpty()
        val bookIds=overlayExport?.bookIds ?: createdArchive?.bookIds ?: bodySource?.settings?.optJSONArray("privateBodyBookIds")?.let { ids ->
            (0 until ids.length()).map {ids.getString(it)}.toSet()
        }.orEmpty()
        val snapshotSettings = org.json.JSONObject().put("backendScope",scope).put("contentGeneration",client.knownContentGeneration(session)).put("privateBodyBookIds",JSONArray(bookIds.sorted())).put("snapshotContentLayout",3).put("libraryEpoch",bootstrap.libraryEpoch)
            .put("ownerUserId",session.userId).put("snapshotContentHashV3",contentHash).put("privateBodyArticleIds",JSONArray(bodyIds.sorted()))
        if (privateFingerprint != null && (!fullTransfer || privateFingerprint == backupService.privateCloudFingerprint())) {
            snapshotSettings.put("privateBodyFingerprintV2",privateFingerprint)
        }
        val encrypted = try { codec.create(
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
            authenticatedObjectHashes = authenticatedHashes,
            privateOverlay = overlayExport!=null || carried.any{it.name==CloudPrivateOverlay.NAME},
            onReusedChunk = { descriptor, materialize ->
                if (cache.loadChunk(client.scopeId(session), descriptor.ciphertextSha256) == null) {
                    cache.storeChunk(client.scopeId(session), descriptor.ciphertextSha256, materialize())
                }
            }
        ) } finally { createdArchive?.file?.delete() }
        encrypted.newCiphertextChunks.forEach { (sha, bytes) ->
            cache.storeChunk(client.scopeId(session), sha, bytes)
        }
        checkCloudEpoch(session, bootstrap.libraryEpoch)
        cache.storePendingManifest(client.scopeId(session), encrypted.manifest.snapshotId, encrypted.encryptedManifest,
            encrypted.manifest.allChunks.map { it.ciphertextSha256 })
        traceSync("upload-chunks:new=${encrypted.newCiphertextChunks.size}:total=${encrypted.manifest.allChunks.size}:newBytes=${encrypted.newCiphertextChunks.values.sumOf { it.size.toLong() }}")
        update(CloudSyncPhase.UPLOADING, "正在预留云空间", bootstrap.member)
        if(overlayExport!=null && !formatWritePolicy.canWrite(client.formatCapabilities(session)))throw CloudFormatUpgradeRequired("overlay_write_disabled")
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
        if(overlayExport!=null && !formatWritePolicy.canWrite(client.formatCapabilities(session)))throw CloudFormatUpgradeRequired("overlay_write_disabled")
        client.completeSnapshot(session, encrypted, bootstrap.libraryEpoch)
        traceSync("upload-verify:done")
        recordStore.clearSnapshotPending(client.scopeId(session))
        _formatStatus.value=recordStore.formatStatus(client.scopeId(session))
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
        checkCloudEpoch(session, bootstrap.libraryEpoch)
        settings.markUploaded(contentHash,parentHeads,full = false)
        settings.lastSnapshotPublishedAt = System.currentTimeMillis()
        return UploadOutcome(true, uploadedBytes, confirmedArticleIds=if (fullTransfer && privateFingerprint == backupService.privateCloudFingerprint()) bodyIds else emptySet(),
            confirmedBookIds=if (fullTransfer && privateFingerprint == backupService.privateCloudFingerprint()) bookIds else emptySet())
        } finally {createdArchive?.file?.delete()}
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
        val complete: Boolean,
        val warningsOnly: Boolean
    )

    private data class UploadOutcome(val uploaded: Boolean, val bytes: Long, val confirmedArticleIds: Set<String> = emptySet(), val deferredUntil: Long = 0, val confirmedBookIds: Set<String> = emptySet())

    private data class DecodedSnapshotObjects(
        val download: CloudSnapshotDownload,
        val objects: Map<String, ByteArray>,
        val manifest: CloudSnapshotManifest
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
