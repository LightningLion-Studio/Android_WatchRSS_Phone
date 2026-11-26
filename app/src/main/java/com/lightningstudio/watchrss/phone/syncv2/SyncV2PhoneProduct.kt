package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.cloud.CloudHeadReply
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2CommitWindow
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.local.ARTICLE_CONTENT_MARKER_PREFIX
import com.lightningstudio.watchrss.phone.data.local.ArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.FileArticleContentStore
import com.lightningstudio.watchrss.phone.data.local.hasReadableArticleBody
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException

private const val MAX_ARTICLE_MARKER_BYTES = 256

// The SQL probe must keep the same blank-text semantics as Kotlin's reader.
private val articleBodyWhitespace = buildString {
    for (character in Char.MIN_VALUE..Char.MAX_VALUE) {
        if (character.isWhitespace()) append(character)
    }
}

// Return a file marker or a representative value for blank/nonblank inline content.
private fun articleBodyAvailabilitySql(column: String): String = """
    CASE
        WHEN substr($column, 1, ${ARTICLE_CONTENT_MARKER_PREFIX.length}) = '$ARTICLE_CONTENT_MARKER_PREFIX'
             AND length(CAST($column AS BLOB)) <= $MAX_ARTICLE_MARKER_BYTES THEN $column
        WHEN length(CAST(ltrim($column, ?1) AS BLOB)) > 0 THEN '1'
        ELSE ''
    END
""".trimIndent()

/** Host account values come from the currently authenticated v1 client on every request. */
internal class SyncV2PhoneProduct(private val context:Context,private val database:()->PhoneCompanionDatabase,
    private val keys:CloudKeyManager,private val current:()->Account?,
    private val verifyKeys:(Account,Map<Int,ByteArray>)->Unit,private val approved:(String)->Boolean,
    private val enabled:()->Boolean={BuildConfig.SYNC_V2},
    private val signals:()->SyncV2TransportSignals={SyncV2AndroidTransport.sample(context)},
    private val clock:()->Long=android.os.SystemClock::elapsedRealtime,
    private val contentEnabled:Boolean=true,
    private val onAuthenticationResponse:(String,Int,String)->Unit={_,_,_->},
    private val cloudResetSupported: Boolean = false,
    private val onCloudReset: (String, Boolean) -> Unit = { _, _ -> }) {
    data class Account(val userId:String,val backendBaseUrl:String,val legacyDeviceId:String,val epoch:String,val generation:String,
        val bearer:String,val apiKey:String,val deviceAuthorization:String?,val sessionGeneration:Long=0, val resetScope: String? = null) {
        fun owner()=listOf(userId,backendBaseUrl,legacyDeviceId,epoch,generation,sessionGeneration.toString(), resetScope.orEmpty())
        val accountId get()=userId.replace("-","").lowercase()
    }
    data class Result(
        val enabled: Boolean,
        val reason: String? = null,
        val awaitingChoice: Boolean = false,
        val pendingAction: Boolean = false,
        val pendingBodies: Boolean = false,
        val quotaBlocked: Boolean = false
    )
    private val mutex=Mutex()
    private val generation=SyncV2Lifetime.Generation()
    @Volatile private var opening:SyncV2Lifetime?=null
    @Volatile private var runtime:Runtime?=null
    @Volatile private var announcement:JSONObject?=null
    private val _uiPort=MutableStateFlow<SyncV2PhoneUiPort?>(null)
    val uiPort:StateFlow<SyncV2PhoneUiPort?> = _uiPort
    val productEnabled: Boolean get() = enabled()

    /** Called before v1 credential deletion, even while start is waiting for HTTP. */
    fun release() {
        generation.invalidate()
        opening?.close();runtime?.life?.close()
        opening=null;runtime=null;announcement=null;_uiPort.value=null
    }
    fun observeBootstrap(syncV2: JSONObject?) {
        if (syncV2 == null && announcement != null) release()
    }

    internal fun hasHeadRuntimeFor(userId: String): Boolean = runtime?.account?.userId == userId

    internal fun headOwner(userId: String): List<String>? {
        val live = runtime?.takeIf { it.account.userId == userId } ?: return null
        val owner = live.account.owner()
        return try {
            if (current()?.owner() == owner) owner + live.identity.repoId else null
        } catch (_: IllegalStateException) {
            null
        }
    }

    internal suspend fun probeHead(owner: List<String>, etag: String?): CloudHeadReply? {
        if (!mutex.tryLock()) return null
        return try {
            val live = runtime ?: return null
            if (headOwner(live.account.userId) != owner) return null
            live.client.accountHead(etag)
        } finally {
            mutex.unlock()
        }
    }

    internal fun hasPendingHeadPublication(owner: List<String>): Boolean {
        val live = runtime ?: return false
        return headOwner(live.account.userId) == owner && live.projection.hasPendingHeadPublication()
    }

    internal fun captureBluetoothWindow(owner: String): B2CommitWindow? {
        val live = runtime?.takeIf { it.account.userId == owner } ?: return null
        val inbox = SyncV2BluetoothInbox(live.projection, context.filesDir)
        return B2CommitWindow(prepareInbox = inbox::prepare, prepareLegacy = inbox::prepareLegacy,
            currentHead = live.projection::bluetoothHeadIsCurrent) {
            if (runtime !== live || current()?.owner() != live.account.owner()) {
                throw B2Exception("AUTH_REVOKED")
            }
            try {
                live.projection.bluetoothVersion()
            } catch (_: IllegalStateException) {
                throw B2Exception("BUSINESS_REJECTED")
            }
        }
    }

    internal fun legacyRollbackWindow(): String? = runtime?.projection?.let {
        it.scope + ":" + it.legacyRollbackGeneration()
    }

    internal fun binaryWindowKey(): String? {
        val live = runtime ?: return null
        return try {
            bluetoothBodyHandoffKey(live.projection.bluetoothVersion().head)
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun captureLegacyMetadata(peer:String,version:Int,cursor:String,payload:JSONObject) {
        if(!enabled() || announcement==null)return
        val live=runtime?:return
        runCatching {live.life.guard {
            live.projection.receiveLegacy(SyncV2Projection.LegacyInput(peer,version,cursor,JSONObject().put("legacy-frame",payload.toString())),null,false)
        }}.onFailure{_uiPort.value?.failed(it)}
    }
    suspend fun sync(
        syncV2: JSONObject?,
        manual: Boolean = false,
        restoreCurrent: Boolean = false,
        expectedUserId: String? = null,
        initializeOnly: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        if(!enabled() || syncV2==null) {
            release()
            return@withContext Result(false,if(!enabled())"feature_disabled"else"not_announced")
        }
        val context = kotlin.coroutines.coroutineContext
        try {
            mutex.withLock {
                SyncV2CloudClient.runCancellable {
                    syncLocked(syncV2, manual, restoreCurrent, expectedUserId, initializeOnly, context::ensureActive)
                }
            }
        } catch(error:SyncV2CloudError) {
            // A closed v2 gate pauses this round without entering the retired legacy lane.
            if(error.code!="feature_disabled")throw error
            _uiPort.value?.disabled()
            Result(false,"feature_disabled")
        }
    }

    private fun syncLocked(
        syncV2: JSONObject, manual: Boolean, restoreCurrent: Boolean, expectedUserId: String?, initializeOnly: Boolean, checkActive: () -> Unit
    ): Result {
        val account=current()?:SyncV2Codec.fail("authentication_required")
        if (expectedUserId != null && account.userId != expectedUserId) {
            SyncV2Codec.fail("authentication_required")
        }
        val old=runtime
        if(old!=null && old.account.owner()!=account.owner()) {
            release()
        }
        announcement = JSONObject(syncV2.toString())
        if(runtime?.serviceAnnouncement?.let{it!=SyncV2Codec.canonical(syncV2)}==true) {
            val error=SyncV2CloudError("service_key_changed");_uiPort.value?.failed(error);throw error
        }
        val live=runtime?:open(account,syncV2,allowCreate=!restoreCurrent)
        live.life.check()
        live.life.guard {live.keys.refreshAuthenticated(keys,account.userId){verifyKeys(account,it)}}
        live.projection.captureResetSources(checkActive)
        if (initializeOnly) {
            if (live.signingDeviceId() == null) {
                // Establish authenticated time before building a new signing binding.
                live.cloud.readCurrent()
                live.actions.prepareSigning()
            }
            return Result(true)
        }
        return try {
            live.round(manual, restoreCurrent)
            _uiPort.value?.refresh()
            val state = live.projection.state().body
            Result(
                enabled = true,
                awaitingChoice = !state.isNull("pendingChoice"),
                pendingAction = !state.isNull("pendingAccountAction") ||
                    !state.isNull("pendingAppliedAck") ||
                    (restoreCurrent && !state.isNull("pendingPublish")),
                pendingBodies = live.bodyResult.pending > 0 || live.bodyResult.errors.isNotEmpty() ||
                    live.hasMissingImportedArticleBody(FileArticleContentStore(context)),
                quotaBlocked = "quota_exceeded" in live.bodyResult.errors
            )
        } catch(e:Throwable) {
            if (e !is CancellationException) _uiPort.value?.failed(e)
            throw e
        }
    }
    private fun open(account:Account,syncV2:JSONObject,allowCreate:Boolean):Runtime {
        val owner=account.owner();val serviceAnnouncement=SyncV2Codec.canonical(syncV2)
        val life=SyncV2Lifetime(generation) {enabled() && announcement?.let{SyncV2Codec.canonical(it)}==serviceAnnouncement && current()?.owner()==owner}
        opening=life
        try {
            life.own(keys.onAccountRelease(account.userId){release()})
            val ring=life.guard {life.own(SyncV2ContentKeys.authenticated(keys,account.userId){verifyKeys(account,it)})}
            val store=SyncV2AccountStore(keys,account.userId,life,account.resetScope)
            val resetScope = account.resetScope?.let(::JSONObject)
            val db = database().openHelper.writableDatabase
            val resetSources = account.resetScope?.let {
                val depot = File(context.noBackupFilesDir, "cloud-reset/${account.accountId}")
                life.own(SyncV2ResetSources.open(db, store, it, context.filesDir, depot))
            }
            val aliases=SyncV2DeviceAlias(store,account.accountId,account.legacyDeviceId,account.epoch,account.generation)
            val trust=SyncV2ServiceTrust.fromBootstrap(account.accountId,syncV2,store.read("service-pin")){store.write("service-pin",it)}
            val time=life.own(SyncV2TrustedTime(clock))
            val base=SyncV2Origin.endpoint(account.backendBaseUrl)
            fun client(scope:JSONObject,deviceId:String):SyncV2CloudClient=life.own(SyncV2CloudClient(base,{
                life.check();val current=current()?:SyncV2Codec.fail("authentication_required")
                SyncV2CloudClient.Session(current.userId,current.legacyDeviceId,current.epoch,current.generation,SyncV2Codec.scope(scope),deviceId,current.bearer,current.apiKey,current.deviceAuthorization)
            },trust,true,authenticatedBackend=account.backendBaseUrl.toHttpUrl(),trustedTime=time,onAuthenticationResponse=onAuthenticationResponse, cloudResetSupported=cloudResetSupported,
                onCloudReset=onCloudReset))
            val pending=store.pending()
            var capsule:JSONObject?=null
            var discovery=client(resetScope ?: JSONObject().put("repoId","00".repeat(16)).put("libraryEpoch","1").put("contentGeneration","1"),"00".repeat(16))
            var descriptor:JSONObject?=null
            val initRejected=pending?.optBoolean("initRejected")==true
            if(pending==null || initRejected || !allowCreate) {
                discovery.capabilities()
                descriptor=try{discovery.descriptor().takeUnless{it.isNull("descriptor")}}catch(e:SyncV2CloudError){if(e.code!="object_missing")throw e;null}
            }
            if(!allowCreate && descriptor==null)throw SyncV2CloudError("current_library_missing")
            if(initRejected && descriptor==null)SyncV2Codec.fail("object_missing")
            if(descriptor==null) {
                if(resetScope==null && pending==null && store.read("pin")!=null)SyncV2Codec.fail("identity_conflict")
                val candidate=life.guard {
                    val alias=aliases.bootstrap(pending)
                    SyncV2RepoBootstrap.prepareStored(account.accountId,account.legacyDeviceId,account.epoch,account.generation,ring.newestVersion,ring::read,store::pending,store::pending,resetScope) {alias}
                }
                discovery=client(candidate.scope,candidate.descriptor.getJSONObject("body").getString("creatorDeviceId"))
                discovery.capabilities()
                descriptor=try {
                    discovery.repoInit(candidate.descriptor,candidate.recoveryCapsule)
                    capsule=candidate.recoveryCapsule
                    JSONObject().put("descriptor",candidate.descriptor).put("descriptorOid",SyncV2Codec.oid("repo-descriptor",candidate.descriptor)).put("recoveryOid",SyncV2Codec.oid("id-key-recovery",candidate.recoveryCapsule))
                } catch(e:SyncV2CloudError) {
                    if(e.code!="ref_conflict")throw e
                    // Persist the CAS rejection before GET. An unknown outcome still replays exact bytes.
                    life.guard {store.pending((store.pending()?:SyncV2Codec.fail("identity_conflict")).put("initRejected",true))}
                    discovery.descriptor()
                }
            }
            life.check()
            val descriptorBody=descriptor!!.getJSONObject("descriptor").getJSONObject("body")
            if (account.resetScope != null && SyncV2Codec.scope(descriptorBody.getJSONObject("scope")) != account.resetScope) {
                SyncV2Codec.fail("scope_mismatch")
            }
            val wrappingVersion=SyncV2Wire.uint(descriptorBody,"wrappingKeyVersion",4294967295)
            if(wrappingVersion==0L)SyncV2Codec.fail("invalid_schema")
            ring.read(wrappingVersion)
            val recoveryOid=descriptor.getString("recoveryOid")
            val recovery=capsule?:SyncV2Wire.parse(discovery.recovery(recoveryOid),8192,true)
            val identity=life.guard {life.own(store.install(descriptor.getJSONObject("descriptor"),recovery,descriptor.getString("descriptorOid"),recoveryOid,ring))}
            val local=life.guard {store.localKey(identity.repoId).also{raw->life.own(AutoCloseable{raw.fill(0)})}}
            val path=SyncV2Codec.hash(JSONObject().put("backend",account.backendBaseUrl).put("account",account.accountId).put("repo",identity.repoId))
            val vault=life.guard {life.own(SyncV2SigningVault(store,identity.repoId,File(context.noBackupFilesDir,"syncv2/$path.sequence"),life,aliases.installed(identity.repoId)))}
            if(vault.signerOrNull()!=null)aliases.reconcile(vault,descriptor.getJSONObject("descriptor")) {author->
                client(identity.scope,author.deviceId).use {probe->
                    probe.capabilities();probe.checkpoint()
                    val window=probe.negotiate();val known=probe.signingKeys(window.getString("windowId"))
                    val bindings=known.getJSONArray("bindings");val authors=probe.authors(identity,approved)
                    var registered=false
                    for(i in 0 until bindings.length()) {
                        val binding=bindings.getJSONObject(i);authors.accept(binding,known.getString("revocationRevision"))
                        val b=binding.getJSONObject("body")
                        if(b.getString("deviceId")==author.deviceId) {
                            if(b.getString("keyId")!=author.keyId || b.getString("publicKey")!=SyncV2Codec.b64(SyncV2Codec.unhex(author.publicHex,65)) ||
                                binding.getJSONObject("deviceMapping").getJSONObject("body").getString("legacyDeviceId")!=account.legacyDeviceId)SyncV2Codec.fail("device_alias_recovery_required")
                            registered=true
                        }
                    }
                    if(registered)true else try {
                        probe.registerSigningKey(identity.signingBinding(author,time.now()?:SyncV2Codec.fail("trusted_time_unavailable"),known.getString("revocationRevision")))
                        true
                    } catch(e:SyncV2CloudError) {if(e.code=="invalid_schema")false else throw e}
                }
            }
            val signer=vault.signerOrNull()
            val deviceId=signer?.deviceId?:"00".repeat(16)
            val control=client(identity.scope,deviceId)
            val contentClient=client(identity.scope,deviceId)
            val projection=life.guard {life.own(SyncV2Projection(db,identity,local,true,
                noteSegments=BuildConfig.SYNC_OBJECTS_V2_NOTE_SEGMENTS,lifetime=life,sequenceAllocator=vault::reserve,resetSources=resetSources).also{it.install()})}
            val content=life.guard {life.own(SyncV2ContentStore(File(context.filesDir,"syncv2/$path"),identity.scopeJson,local.copyOf()))}
            val cloud=SyncV2CloudSync(control,identity,projection,approved,ring::read,{time.now()?:System.currentTimeMillis()})
            val actions=life.guard {life.own(SyncV2AccountActions(control,identity,projection,signer,ring::read,approved,context.filesDir,true,time::now,
                noteSegmentsRead=BuildConfig.SYNC_OBJECTS_V2_NOTE_SEGMENTS,currentKeyVersion={ring.newestVersion}))}
            val worker=SyncV2ContentWorker(contentClient,identity,content,signals,enabled=contentEnabled,clock=clock,now={time.now()?:System.currentTimeMillis()},approvedDevice={device->
                val bindings=projection.state().body.optJSONArray("signingBindings")
                bindings!=null && (0 until bindings.length()).any {index->
                    val b=bindings.getJSONObject(index)
                    b.getJSONObject("body").getString("deviceId")==device && approved(b.getJSONObject("deviceMapping").getJSONObject("body").getString("legacyDeviceId"))
                }
            })
            val articles=signer?.let{SyncV2ArticlePublisher(projection,identity,ring,it,contentClient,content,worker,context.filesDir,life::check,clock)}
            val bodies=signer?.let{SyncV2BodyChannel(projection,identity,ring,it,control,SyncV2BodyRepository(projection.accountDatabase,context.filesDir),
                File(context.filesDir,"syncv2/$path/bodies"),life,approved,signals,{time.now()?:System.currentTimeMillis()})}
            val result=Runtime(account.copy(bearer="",apiKey="",deviceAuthorization=null),life,ring,identity,vault,projection,cloud,actions,control,articles,bodies,time,contentEnabled,serviceAnnouncement)
            life.guard {
                val port=SyncV2PhoneUiPort(this,result)
                life.own(AutoCloseable{port.disabled()})
                runtime=result;_uiPort.value=port;opening=null
            }
            return result
        }catch(e:Throwable){life.close();if(opening===life)opening=null;throw e}
    }
    internal suspend fun <T> action(port:SyncV2PhoneUiPort,run:(Runtime)->T):T=withContext(Dispatchers.IO) {
        mutex.withLock {
            val live=runtime?:SyncV2Codec.fail("authentication_required")
            if(_uiPort.value!==port)SyncV2Codec.fail("authentication_required")
            live.life.check()
            try {
                SyncV2CloudClient.runCancellable { run(live).also { port.refresh() } }
            } catch (error: Throwable) {
                if (error !is CancellationException) port.failed(error)
                throw error
            }
        }
    }
    internal class Runtime(val account:Account,val life:SyncV2Lifetime,val keys:SyncV2ContentKeys,val identity:SyncV2Identity,
        val vault:SyncV2SigningVault,val projection:SyncV2Projection,val cloud:SyncV2CloudSync,val actions:SyncV2AccountActions,
        val client:SyncV2CloudClient,val articles:SyncV2ArticlePublisher?,val bodies:SyncV2BodyChannel?,val time:SyncV2TrustedTime,val contentEnabled:Boolean,val serviceAnnouncement:String) {
        var lastSyncedAt:Long?=null
        var bodyResult=SyncV2BodyChannel.Result(0,emptyList())
        fun signingDeviceId():String? {
            life.check();val signer=vault.signerOrNull()?:return null
            val bindings=projection.state().body.optJSONArray("signingBindings")?:return null
            return signer.deviceId.takeIf {id->(0 until bindings.length()).any {index->
                val b=bindings.getJSONObject(index).getJSONObject("body")
                b.optString("deviceId")==id && b.optString("publicKey")==SyncV2Codec.b64(SyncV2Codec.unhex(signer.publicHex,65))
            }}
        }
        // Article recipes can be published, but the phone has no article recipe receiver yet.
        fun hasMissingImportedArticleBody(store: ArticleContentStore): Boolean = life.guard {
            val db = projection.accountDatabase
            val missing = db.controlQuery(
                """SELECT articleId, url, ${articleBodyAvailabilitySql("contentText")}, ${articleBodyAvailabilitySql("contentHtml")}
                    FROM phone_articles
                    WHERE deleted=0 AND independentSaved=1
                    AND (url LIKE '%watchrss.local/import%' OR url LIKE '%watchrss://import/%')""",
                arrayOf(articleBodyWhitespace)
            ).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val url = rows.getString(1)
                        if (ImportedContentIds.isImportedContentUrl(url) &&
                            !hasReadableArticleBody(url, rows.getString(2), rows.getString(3), store)
                        ) add(rows.getString(0))
                    }
                }
            }
            missing.any { id ->
                db.controlQuery("SELECT 1 FROM syncv2_cloud_rows WHERE scope=? AND entity_key=?",
                    arrayOf(projection.scope, identity.entity("article", id))).use { it.moveToFirst() }
            }
        }

        fun round(manual:Boolean=false,restoreCurrent:Boolean=false) {
            life.check()
            val started=android.os.SystemClock.elapsedRealtime()
            fun remainingMillis() = 290_000L - (android.os.SystemClock.elapsedRealtime() - started)
            fun remaining(): Long = remainingMillis().also {
                if (it <= 0) throw SyncV2CloudError("sync_timeout")
            }
            try {
                roundWithinBudget(manual, restoreCurrent, ::remaining)
                remaining()
            } catch (error: Exception) {
                if (error !is CancellationException && remainingMillis() <= 0 &&
                    (error.message == "budget_paused" || error is InterruptedIOException)) {
                    throw SyncV2CloudError("sync_timeout")
                }
                throw error
            }
        }

        private fun roundWithinBudget(manual: Boolean, restoreCurrent: Boolean, remaining: () -> Long) {
            client.withinBudget(remaining()) {
            if(vault.signerOrNull()==null) {
                actions.refresh(budgetMillis=remaining())
                SyncV2Codec.fail("signing_state_unavailable")
            }
            // Reconcile the original signed attempt before any new-account or new-source guard.
            if (!restoreCurrent) cloud.resumePublish()
            val current = cloud.readCurrent()
            if (restoreCurrent && current == "empty-account") {
                throw SyncV2CloudError("current_library_missing")
            }
            if(signingDeviceId()==null)actions.prepareSigning()
            actions.refresh(budgetMillis=remaining())
            val referenceErrors=if(contentEnabled)life.guard{bodies?.refreshReferences()?:emptyList()}else emptyList()
            val progress=projection.runWindow(budgetMillis=remaining())
            if(progress.paused)SyncV2Codec.fail("budget_paused")
            var st=projection.state()
            if(!st.body.isNull("pendingAccountAction")){
                if (restoreCurrent) return@withinBudget
                actions.resume();st=projection.state()
            }
            if (!st.body.isNull("pendingChoice") || (restoreCurrent && !st.body.isNull("pendingPublish"))) return@withinBudget
            val unacknowledged = st.revision != st.body.optString("acknowledgedLocalRevision", "0")
            if (restoreCurrent) {
                if (unacknowledged || st.body.optBoolean("unknownEditBase")) {
                    projection.requestConcurrentChoice()
                } else {
                    actions.flushAppliedAck()
                    val result = bodies?.sync(manual, downloadOnly = true) ?: SyncV2BodyChannel.Result(0, emptyList())
                    bodyResult = SyncV2BodyChannel.Result(result.pending + referenceErrors.size,
                        (referenceErrors + result.errors).distinct())
                    if (projection.state().body.isNull("pendingAppliedAck")) lastSyncedAt = time.now()
                }
                return@withinBudget
            }
            val localDirty=unacknowledged || st.body.isNull("activeGeneration")
            val dirty=localDirty || (contentEnabled && bodies?.needsPublication()==true)
            if(dirty) {
                val publicationRevision=st.revision
                val accountHead=st.body.optJSONObject("account")
                if(localDirty && accountHead!=null && (st.body.optBoolean("unknownEditBase") || st.body.optJSONObject("editBase")?.optString("accountOid")!=accountHead.getString("oid"))) {
                    projection.requestConcurrentChoice();return@withinBudget
                }
                actions.prepareSigning()
                projection.prepareWireIndex(vault.signer,keys.read(keys.newestVersion),keys.newestVersion).close()
                if(contentEnabled)bodies?.bindPrepared()
                if(contentEnabled && client.contentWriteAllowed() && !articles!!.runPage(remaining()))SyncV2Codec.fail("budget_paused")
                cloud.publishIndex(vault.signer,keys.read(keys.newestVersion),keys.newestVersion,publicationRevision)
                actions.refresh(budgetMillis=remaining())
            } else actions.flushAppliedAck()
            if(contentEnabled) {
                val result=bodies?.sync(manual)?:SyncV2BodyChannel.Result(0,emptyList())
                bodyResult=SyncV2BodyChannel.Result(result.pending+referenceErrors.size,(referenceErrors+result.errors).distinct())
            }
            st=projection.state()
            if(!st.body.isNull("appliedAccount") && st.body.isNull("pendingAppliedAck"))lastSyncedAt=time.now()
            }
        }
    }
}
