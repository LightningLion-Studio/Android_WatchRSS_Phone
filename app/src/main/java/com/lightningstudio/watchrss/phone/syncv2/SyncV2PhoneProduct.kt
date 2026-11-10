package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Host account values come from the currently authenticated v1 client on every request. */
internal class SyncV2PhoneProduct(private val context:Context,private val database:()->PhoneCompanionDatabase,
    private val keys:CloudKeyManager,private val current:()->Account?,
    private val verifyKeys:(Account,Map<Int,ByteArray>)->Unit,private val approved:(String)->Boolean,
    private val enabled:()->Boolean={BuildConfig.SYNC_V2},
    private val signals:()->SyncV2TransportSignals={SyncV2AndroidTransport.sample(context)},
    private val clock:()->Long=android.os.SystemClock::elapsedRealtime,
    private val contentEnabled:Boolean=true) {
    data class Account(val userId:String,val backendBaseUrl:String,val legacyDeviceId:String,val epoch:String,val generation:String,
        val bearer:String,val apiKey:String,val deviceAuthorization:String?) {
        fun owner()=listOf(userId,backendBaseUrl,legacyDeviceId,epoch,generation)
        val accountId get()=userId.replace("-","").lowercase()
    }
    data class Result(val enabled:Boolean,val reason:String?=null)
    private val mutex=Mutex()
    private val generation=AtomicLong()
    @Volatile private var opening:SyncV2Lifetime?=null
    @Volatile private var runtime:Runtime?=null
    @Volatile private var announcement:JSONObject?=null
    private val _uiPort=MutableStateFlow<SyncV2PhoneUiPort?>(null)
    val uiPort:StateFlow<SyncV2PhoneUiPort?> = _uiPort

    /** Called before v1 credential deletion, even while start is waiting for HTTP. */
    fun release() {
        generation.incrementAndGet()
        opening?.close();runtime?.life?.close()
        opening=null;runtime=null;announcement=null;_uiPort.value=null
    }
    fun captureLegacyMetadata(peer:String,version:Int,cursor:String,payload:JSONObject) {
        if(!enabled() || announcement==null)return
        val live=runtime?:return
        runCatching {live.life.guard {
            live.projection.receiveLegacy(SyncV2Projection.LegacyInput(peer,version,cursor,JSONObject().put("legacy-frame",payload.toString())),null,false)
        }}.onFailure{_uiPort.value?.failed(it)}
    }
    suspend fun sync(syncV2:JSONObject?):Result=withContext(Dispatchers.IO) {
        if(!enabled() || syncV2==null) {
            announcement=null
            _uiPort.value?.disabled()
            return@withContext Result(false,if(!enabled())"feature_disabled"else"not_announced")
        }
        announcement=JSONObject(syncV2.toString())
        mutex.withLock {
            val account=current()?:SyncV2Codec.fail("authentication_required")
            val old=runtime
            if(old!=null && old.account.owner()!=account.owner()) {
                release();announcement=JSONObject(syncV2.toString())
            }
            if(runtime?.serviceAnnouncement?.let{it!=SyncV2Codec.canonical(syncV2)}==true) {
                val error=SyncV2CloudError("service_key_changed");_uiPort.value?.failed(error);throw error
            }
            val live=runtime?:open(account,syncV2)
            live.life.check()
            live.life.guard {live.keys.refreshAuthenticated(keys,account.userId){verifyKeys(account,it)}}
            try {live.round();_uiPort.value?.refresh();Result(true)}catch(e:Throwable) {
                _uiPort.value?.failed(e)
                throw e
            }
        }
    }
    private fun open(account:Account,syncV2:JSONObject):Runtime {
        val gen=generation.get()
        val owner=account.owner();val serviceAnnouncement=SyncV2Codec.canonical(syncV2)
        val life=SyncV2Lifetime {generation.get()==gen && enabled() && announcement?.let{SyncV2Codec.canonical(it)}==serviceAnnouncement && current()?.owner()==owner}
        opening=life
        try {
            life.own(keys.onAccountRelease(account.userId){release()})
            val ring=life.guard {life.own(SyncV2ContentKeys.authenticated(keys,account.userId){verifyKeys(account,it)})}
            val store=SyncV2AccountStore(keys,account.userId,life)
            val trust=SyncV2ServiceTrust.fromBootstrap(account.accountId,syncV2,store.read("service-pin")){store.write("service-pin",it)}
            val time=life.own(SyncV2TrustedTime(clock))
            val base=SyncV2Origin.endpoint(account.backendBaseUrl)
            fun client(scope:JSONObject,deviceId:String):SyncV2CloudClient=life.own(SyncV2CloudClient(base,{
                life.check();val current=current()?:SyncV2Codec.fail("authentication_required")
                SyncV2CloudClient.Session(current.userId,current.legacyDeviceId,current.epoch,current.generation,SyncV2Codec.scope(scope),deviceId,current.bearer,current.apiKey,current.deviceAuthorization)
            },trust,true,authenticatedBackend=account.backendBaseUrl.toHttpUrl(),trustedTime=time))
            val pending=store.pending()
            var capsule:JSONObject?=null
            var discovery=client(JSONObject().put("repoId","00".repeat(16)).put("libraryEpoch","1").put("contentGeneration","1"),"00".repeat(16))
            var descriptor:JSONObject?=null
            val initRejected=pending?.optBoolean("initRejected")==true
            if(pending==null || initRejected) {
                discovery.capabilities()
                descriptor=try{discovery.descriptor().takeUnless{it.isNull("descriptor")}}catch(e:SyncV2CloudError){if(e.code!="object_missing")throw e;null}
            }
            if(initRejected && descriptor==null)SyncV2Codec.fail("object_missing")
            if(descriptor==null) {
                if(pending==null && store.read("pin")!=null)SyncV2Codec.fail("identity_conflict")
                val candidate=life.guard {SyncV2RepoBootstrap.prepareStored(account.accountId,account.legacyDeviceId,account.epoch,account.generation,ring.newestVersion,ring::read,store::pending,store::pending)}
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
            val wrappingVersion=SyncV2Wire.uint(descriptorBody,"wrappingKeyVersion",4294967295)
            if(wrappingVersion==0L)SyncV2Codec.fail("invalid_schema")
            ring.read(wrappingVersion)
            val recoveryOid=descriptor.getString("recoveryOid")
            val recovery=capsule?:SyncV2Wire.parse(discovery.recovery(recoveryOid),8192,true)
            val identity=life.guard {life.own(store.install(descriptor.getJSONObject("descriptor"),recovery,descriptor.getString("descriptorOid"),recoveryOid,ring))}
            val local=life.guard {store.localKey(identity.repoId).also{raw->life.own(AutoCloseable{raw.fill(0)})}}
            val path=SyncV2Codec.hash(JSONObject().put("backend",account.backendBaseUrl).put("account",account.accountId).put("repo",identity.repoId))
            val vault=life.guard {life.own(SyncV2SigningVault(store,identity.repoId,File(context.noBackupFilesDir,"syncv2/$path.sequence"),life))}
            val signer=vault.signerOrNull()
            val deviceId=signer?.deviceId?:"00".repeat(16)
            val control=client(identity.scope,deviceId)
            val contentClient=client(identity.scope,deviceId)
            val projection=life.guard {life.own(SyncV2Projection(database().openHelper.writableDatabase,identity,local,true,
                noteSegments=BuildConfig.SYNC_OBJECTS_V2_NOTE_SEGMENTS,lifetime=life,sequenceAllocator=vault::reserve).also{it.install()})}
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
            val result=Runtime(account.copy(bearer="",apiKey="",deviceAuthorization=null),life,ring,identity,vault,projection,cloud,actions,control,articles,time,contentEnabled,serviceAnnouncement)
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
            try {run(live).also{port.refresh()}}catch(e:Throwable){port.failed(e);throw e}
        }
    }
    internal class Runtime(val account:Account,val life:SyncV2Lifetime,val keys:SyncV2ContentKeys,val identity:SyncV2Identity,
        val vault:SyncV2SigningVault,val projection:SyncV2Projection,val cloud:SyncV2CloudSync,val actions:SyncV2AccountActions,
        val client:SyncV2CloudClient,val articles:SyncV2ArticlePublisher?,val time:SyncV2TrustedTime,val contentEnabled:Boolean,val serviceAnnouncement:String) {
        var lastSyncedAt:Long?=null
        fun signingDeviceId():String? {
            life.check();val signer=vault.signerOrNull()?:return null
            val bindings=projection.state().body.optJSONArray("signingBindings")?:return null
            return signer.deviceId.takeIf {id->(0 until bindings.length()).any {index->
                val b=bindings.getJSONObject(index).getJSONObject("body")
                b.optString("deviceId")==id && b.optString("publicKey")==SyncV2Codec.b64(SyncV2Codec.unhex(signer.publicHex,65))
            }}
        }
        fun round() {
            life.check()
            val started=android.os.SystemClock.elapsedRealtime()
            fun remaining():Long=(290000L-(android.os.SystemClock.elapsedRealtime()-started)).also{if(it<=0)SyncV2Codec.fail("budget_paused")}
            client.withinBudget(remaining()) {
            if(vault.signerOrNull()==null) {
                actions.refresh(budgetMillis=remaining())
                SyncV2Codec.fail("signing_state_unavailable")
            }
            // Reconcile the original signed attempt before any new-account or new-source guard.
            cloud.resumePublish()
            cloud.readCurrent()
            if(signingDeviceId()==null)actions.prepareSigning()
            actions.refresh(budgetMillis=remaining())
            val progress=projection.runWindow(budgetMillis=remaining())
            if(progress.paused)SyncV2Codec.fail("budget_paused")
            var st=projection.state()
            if(!st.body.isNull("pendingAccountAction")){actions.resume();st=projection.state()}
            if(!st.body.isNull("pendingChoice"))return@withinBudget
            val dirty=st.revision!=st.body.optString("acknowledgedLocalRevision","0") || st.body.isNull("activeGeneration")
            if(dirty) {
                val publicationRevision=st.revision
                val accountHead=st.body.optJSONObject("account")
                if(accountHead!=null && (st.body.optBoolean("unknownEditBase") || st.body.optJSONObject("editBase")?.optString("accountOid")!=accountHead.getString("oid"))) {
                    projection.requestConcurrentChoice();return@withinBudget
                }
                actions.prepareSigning()
                projection.prepareWireIndex(vault.signer,keys.read(keys.newestVersion),keys.newestVersion).close()
                if(contentEnabled && client.contentWriteAllowed() && !articles!!.runPage(remaining()))SyncV2Codec.fail("budget_paused")
                cloud.publishIndex(vault.signer,keys.read(keys.newestVersion),keys.newestVersion,publicationRevision)
                actions.refresh(budgetMillis=remaining())
            } else actions.flushAppliedAck()
            st=projection.state()
            if(!st.body.isNull("appliedAccount") && st.body.isNull("pendingAppliedAck"))lastSyncedAt=time.now()
            }
        }
    }
}
