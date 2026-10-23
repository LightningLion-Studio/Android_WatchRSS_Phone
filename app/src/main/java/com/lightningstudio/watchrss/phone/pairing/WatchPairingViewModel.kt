package com.lightningstudio.watchrss.phone.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightningstudio.watchrss.phone.account.WatchDeviceToken
import com.lightningstudio.watchrss.phone.connection.bluetooth.OpenPairingRequest
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchQrRequest
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchQrNotOpenedReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

sealed interface PairingCloudPreparation {
    val epoch:String
    data class Ready(override val epoch:String):PairingCloudPreparation
    data class NeedsKey(override val epoch:String,val existingCloud:Boolean):PairingCloudPreparation
}
data class PairingOwner(val userId:String,val backendBaseUrl:String,val epoch:String?,val phoneMasked:String="")
enum class PairingCameraPermission { NOT_REQUESTED, REQUEST_REQUIRED, GRANTED, DENIED, BLOCKED }
sealed interface WatchPairingState {
    data object Idle:WatchPairingState
    data object PreparingCloud:WatchPairingState
    data class NeedsKey(val existingCloud:Boolean):WatchPairingState
    data class Scanning(val permission:PairingCameraPermission):WatchPairingState
    data object Connecting:WatchPairingState
    /** SAS is independently available before claim/prepare completes. Phone may confirm now. */
    sealed class Confirming(open val sas:String):WatchPairingState {
        data class Phone(override val sas:String):Confirming(sas)
        companion object { operator fun invoke(sas:String):Confirming = Phone(sas) }
    }
    sealed class Configuring(open val step:String):WatchPairingState {
        data class Progress(override val step:String):Configuring(step)
        companion object { operator fun invoke(step:String):Configuring = Progress(step) }
    }
    data class WaitingForPrepareAfterPhone(val sas:String):Configuring("已确认配对码，正在等待手表响应")
    data class WaitingWatchConfirmAfterPhone(val sas:String):Configuring("请在手表上点一致，绑定") {
        val message:String get() = step
    }
    data class Done(val bound:Boolean,val cloudReady:Boolean,val cloudError:String?=null,val finishError:String?=null,val canRetry:Boolean=false):WatchPairingState
    data class Failed(val message:String,val canRetry:Boolean=true):WatchPairingState
}
interface WatchPairingPorts {
    val changes:Flow<Unit>
    fun currentOwner():PairingOwner?
    suspend fun prepareCloud(owner:PairingOwner):PairingCloudPreparation
    fun openChannel(qr:WatchPairingQr,owner:PairingOwner):WatchPairingChannel
    suspend fun issueCredentials(qr:WatchPairingQr,owner:PairingOwner):WatchDeviceToken
    suspend fun grantCloud(qr:WatchPairingQr,preparedSpki:String,owner:PairingOwner):PairingCloudGrant
    suspend fun requestWatchQr():WatchQrRequest
}

/** Lead owns layout/launchers. No camera permission is requested by construction or page entry. */
class WatchPairingViewModel internal constructor(private val ports:WatchPairingPorts,
    scope:CoroutineScope?=null,private val now:()->Long=System::currentTimeMillis,
    private val monotonicNow:()->Long=android.os.SystemClock::elapsedRealtime):ViewModel() {
    private val workScope=scope?:viewModelScope
    private val mutable=MutableStateFlow<WatchPairingState>(WatchPairingState.Idle)
    val state:StateFlow<WatchPairingState> = mutable
    private val mutableWatchQrRequest=MutableStateFlow<WatchQrRequest?>(null)
    val watchQrRequest:StateFlow<WatchQrRequest?> = mutableWatchQrRequest.asStateFlow()
    private val generation=AtomicLong()
    private var work:Job?=null
    private var watchQrJob:Job?=null
    private var pageEntered=false
    private var lastWatchQrRequestAt:Long?=null
    private val watchQrGeneration=AtomicLong()
    private var watchQrOwner:PairingOwner?=null
    private var owner:PairingOwner?=null
    private var qr:WatchPairingQr?=null
    private var channel:WatchPairingChannel?=null
    private var cameraGranted=false
    private var prepared:JSONObject?=null
    private var issued:WatchDeviceToken?=null
    private var configureMessage:JSONObject?=null
    private var grant:PairingCloudGrant?=null
    private var bound=false;private var cloudReady=false;private var finished=false;private var sasConfirmed=false
    private val observer=workScope.launch {
        ports.changes.collect {
            if(owner!=null && !sameOwner()) { invalidate("账号或云资料库已变化，请重新开始扫码绑定");return@collect }
            if(watchQrJob?.isActive==true && !sameWatchQrOwner()) clearWatchQrRequest()
            if(state.value is WatchPairingState.NeedsKey && work?.isActive!=true) prepareScan()
        }
    }
    private fun sameOwner():Boolean {
        val pinned=owner?:return false;val current=ports.currentOwner()?:return false
        return current.userId==pinned.userId && current.backendBaseUrl.trimEnd('/')==pinned.backendBaseUrl.trimEnd('/') &&
            (pinned.epoch==null || pinned.epoch==current.epoch)
    }
    private fun check(ticket:Long) { if(generation.get()!=ticket || !sameOwner()) throw PairingInvalidated() }
    private fun launchWork(block:suspend (Long)->Unit) {
        if(work?.isActive==true)return
        val ticket=generation.get()
        work=workScope.launch {
            try { check(ticket);block(ticket) }
            catch(error:CancellationException) { if(generation.get()==ticket && !sameOwner()) invalidate("账号或云资料库已变化，请重新开始扫码绑定") }
            catch(error:Exception) {
                if(generation.get()==ticket) {
                    val ended=(error is PairingException && error.code in setOf("watch_rejected","pairing_expired")) ||
                        (error is PairingHttpException && error.status in setOf(404,410))
                    if(ended)this@WatchPairingViewModel.cancel()
                    mutable.value=WatchPairingState.Failed(error.message?:"配对失败，请重试",canRetry=!ended)
                }
            }
            finally { if(generation.get()==ticket)work=null }
        }
    }
    /** Called only by the user's “扫码绑定” action; pass the existing OS permission, never request on entry. */
    fun startScan(cameraPermissionGranted:Boolean=false) {
        resetPairing();cameraGranted=cameraPermissionGranted
        owner=ports.currentOwner()
        if(owner==null) {mutable.value=WatchPairingState.Failed("请先登录腕上RSS账号",false);return}
        requestWatchQrAgain()
        prepareScan()
    }
    /** Once per retained ViewModel. Lead calls on Activity entry; camera permission remains user initiated. */
    fun onPageEntered() {
        if(pageEntered)return
        pageEntered=true
        requestWatchQr()
    }
    /** Explicit retry only after a failed attempt and a monotonic cooldown strictly greater than 10s. */
    fun requestWatchQrAgain():Boolean {
        val last=lastWatchQrRequestAt?:return false
        if(mutableWatchQrRequest.value !is WatchQrRequest.NotOpened || monotonicNow()-last<=10_000)return false
        requestWatchQr();return true
    }
    private fun sameWatchQrOwner():Boolean {
        val pinned=watchQrOwner;val current=ports.currentOwner()
        if(pinned==null)return current==null
        return current!=null && pinned.userId==current.userId && pinned.backendBaseUrl.trimEnd('/')==current.backendBaseUrl.trimEnd('/') &&
            (pinned.epoch==null || pinned.epoch==current.epoch)
    }
    private fun requestWatchQr() {
        val ticket=watchQrGeneration.incrementAndGet()
        watchQrOwner=ports.currentOwner();lastWatchQrRequestAt=monotonicNow()
        mutableWatchQrRequest.value=WatchQrRequest.Sending
        watchQrJob=workScope.launch {
            val result=try { withTimeout(OpenPairingRequest.TIMEOUT_MS) { ports.requestWatchQr() } }
            catch(_:TimeoutCancellationException) { WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE) }
            if(watchQrGeneration.get()==ticket && sameWatchQrOwner()) mutableWatchQrRequest.value=result
        }
    }
    private fun prepareScan()=launchWork { ticket ->
        mutable.value=WatchPairingState.PreparingCloud
        val cloud=ports.prepareCloud(requireNotNull(owner));check(ticket)
        owner=requireNotNull(owner).copy(epoch=cloud.epoch)
        check(ticket)
        mutable.value=when(cloud) {
            is PairingCloudPreparation.NeedsKey -> WatchPairingState.NeedsKey(cloud.existingCloud)
            is PairingCloudPreparation.Ready -> WatchPairingState.Scanning(if(cameraGranted)PairingCameraPermission.GRANTED else PairingCameraPermission.REQUEST_REQUIRED)
        }
    }
    fun onCameraPermissionResult(granted:Boolean,canAskAgain:Boolean=true) {
        if(state.value !is WatchPairingState.Scanning)return
        cameraGranted=granted
        mutable.value=WatchPairingState.Scanning(when {granted->PairingCameraPermission.GRANTED;canAskAgain->PairingCameraPermission.DENIED;else->PairingCameraPermission.BLOCKED})
    }
    /** Capture once per camera binding so queued frames from an abandoned scan cannot enter a new scan. */
    fun qrCallback():(String)->Unit {
        val ticket=generation.get()
        return { raw -> if(generation.get()==ticket)onQrDecoded(raw) }
    }
    fun onQrDecoded(raw:String) {
        if((state.value as? WatchPairingState.Scanning)?.permission!=PairingCameraPermission.GRANTED)return
        launchWork { ticket ->
            if(state.value !is WatchPairingState.Scanning)return@launchWork
            val descriptor=WatchPairingQr.parse(raw,now());qr=descriptor
            channel=ports.openChannel(descriptor,requireNotNull(owner))
            connectAndPrepare(ticket)
        }
    }
    private suspend fun connectAndPrepare(ticket:Long) {
        val descriptor=requireNotNull(qr);val link=requireNotNull(channel)
        val localSas=PairingWireCodec.sas(descriptor,requireNotNull(owner).userId)
        // R43: neither claim nor a delayed prepare receipt blocks the phone's user.
        mutable.value=if(sasConfirmed) WatchPairingState.WaitingForPrepareAfterPhone(localSas)
            else WatchPairingState.Confirming(localSas)
        try {
            withTimeout(CONNECT_PREPARE_TIMEOUT_MS) {
                link.connect();check(ticket)
                if(prepared==null) {
                    val receipt=link.request(JSONObject().put("action","prepare").put("userId",requireNotNull(owner).userId).put("phoneMasked",requireNotNull(owner).phoneMasked.ifBlank { "当前账号" }))
                    check(ticket)
                    require(receipt.optBoolean("success") && receipt.optString("deviceId")==descriptor.deviceId && receipt.optString("userId")==requireNotNull(owner).userId &&
                        (!receipt.has("installId") || receipt.get("installId")==descriptor.installId)) { "手表身份与配对码不一致，请重新扫码" }
                    val receiptSas=PairingWireCodec.sas(descriptor,receipt.getString("userId"))
                    require(receiptSas==localSas && (!receipt.has("sas") || receipt.get("sas")==localSas)) { "手表配对码不一致，请重新扫码" }
                    val spki=receipt.getString("publicKeySpki")
                    com.lightningstudio.watchrss.phone.cloud.CloudKeyEnvelopeCodec.decodeP256PublicKey(spki)
                    prepared=receipt
                }
                check(ticket)
            }
        } catch(error:TimeoutCancellationException) {
            check(ticket)
            throw PairingException("pairing_timeout","等待手表响应超时，请重试；若配对码过期请重新扫描")
        }
        if(!sasConfirmed) mutable.value=WatchPairingState.Confirming(localSas)
        // If the click arrived while prepare was in flight, this same worker continues;
        // do not start a second claim/request or lose the click to launchWork's busy guard.
        if(sasConfirmed) configure(ticket)
    }
    fun confirmSas(matches:Boolean) {
        val current=state.value
        if(current !is WatchPairingState.Confirming && current !is WatchPairingState.WaitingForPrepareAfterPhone &&
            current !is WatchPairingState.WaitingWatchConfirmAfterPhone)return
        if(!matches) {cancel();mutable.value=WatchPairingState.Failed("两端配对码不一致，已取消；请重新扫描",false);return}
        if(current !is WatchPairingState.Confirming || sasConfirmed)return
        sasConfirmed=true
        if(prepared==null) {
            mutable.value=WatchPairingState.WaitingForPrepareAfterPhone(current.sas)
            return
        }
        launchWork { ticket -> configure(ticket) }
    }
    private suspend fun configure(ticket:Long) {
        check(ticket);check(sasConfirmed && prepared!=null)
        val descriptor=requireNotNull(qr);val link=requireNotNull(channel);val pinned=requireNotNull(owner)
        try {
            if(!bound) {
                mutable.value=WatchPairingState.Configuring("正在签发手表凭据")
                if(issued==null) {val token=ports.issueCredentials(descriptor,pinned);check(ticket);require(token.accessToken.isNotBlank()&&token.refreshToken.isNotBlank()&&token.accessTokenExpiresAtMillis>now()){ "手表凭据未确认" };issued=token}
                if(configureMessage==null)configureMessage=configureMessage(pinned,descriptor,requireNotNull(issued))
                mutable.value=WatchPairingState.WaitingWatchConfirmAfterPhone(PairingWireCodec.sas(descriptor,pinned.userId))
                val receipt=try {
                    withTimeout(WATCH_BOUND_TIMEOUT_MS) { link.request(requireNotNull(configureMessage)) }
                } catch(error:TimeoutCancellationException) {
                    check(ticket)
                    throw PairingException("pairing_timeout","等待手表绑定确认超时，请重试；若配对码过期请重新扫描")
                }
                check(ticket)
                require(receipt.optBoolean("bound")&&receipt.optString("deviceId")==descriptor.deviceId&&receipt.optString("userId")==pinned.userId) { "手表尚未确认保存账号，请重试" }
                bound=true;issued=null;configureMessage=null
            }
            if(!cloudReady) {
                mutable.value=WatchPairingState.Configuring("正在核对手表自行登记并交付云密钥")
                if(grant==null) {val value=ports.grantCloud(descriptor,requireNotNull(prepared).getString("publicKeySpki"),pinned);check(ticket);grant=value}
                val keyGrant=requireNotNull(grant)
                val receipt=link.request(keyGrant.message(pinned.userId,requireNotNull(pinned.epoch)));check(ticket)
                require(receipt.optBoolean("cloudReady")&&receipt.optString("deviceId")==descriptor.deviceId&&receipt.optInt("keyVersion")==keyGrant.keyVersion) { "手表尚未确认云密钥保存，请重试授权" }
                cloudReady=true;grant=null
            }
            if(!finished) {
                mutable.value=WatchPairingState.Configuring("正在结束配对")
                link.request(JSONObject().put("action","finish").put("cloudReady",true));check(ticket);finished=true
            }
            link.close();check(ticket);channel=null;qr=null;prepared=null
            mutable.value=WatchPairingState.Done(true,true)
        } catch(error:CancellationException) {throw error}
        catch(error:Exception) {
            check(ticket)
            if(bound)mutable.value=WatchPairingState.Done(true,cloudReady,
                cloudError=if(!cloudReady)error.message?:"云授权未完成，请重试"else null,
                finishError=if(cloudReady)error.message?:"结束确认未送达，账号已保存"else null,
                canRetry=now()<descriptor.expiresAt)
            else throw error
        }
    }
    fun retry() {
        when(val current=state.value) {
            is WatchPairingState.NeedsKey -> prepareScan()
            is WatchPairingState.Scanning -> if(current.permission!=PairingCameraPermission.GRANTED)mutable.value=WatchPairingState.Scanning(PairingCameraPermission.REQUEST_REQUIRED)
            is WatchPairingState.Done -> if(current.canRetry)launchWork {configure(it)}
            is WatchPairingState.Failed -> if(qr!=null && current.canRetry)launchWork { if(sasConfirmed && prepared!=null)configure(it)else connectAndPrepare(it) } else startScan(cameraGranted)
            else -> Unit
        }
    }
    private fun release() {
        val old=channel;channel=null
        old?.invalidate();qr?.destroy();qr=null;prepared=null;issued=null;configureMessage=null;grant=null
        if(old!=null)workScope.launch(NonCancellable) {runCatching {old.close()} }
    }
    private fun resetPairing() {
        generation.incrementAndGet();work?.cancel();work=null;release();owner=null
        bound=false;cloudReady=false;finished=false;sasConfirmed=false
        mutable.value=WatchPairingState.Idle
    }
    private fun clearWatchQrRequest() {
        watchQrGeneration.incrementAndGet();watchQrJob?.cancel();watchQrJob=null
        mutableWatchQrRequest.value=null;watchQrOwner=null
    }
    fun cancel() {resetPairing();clearWatchQrRequest()}
    private fun invalidate(message:String) {cancel();mutable.value=WatchPairingState.Failed(message,false)}
    override fun onCleared() {cancel();observer.cancel();super.onCleared()}
    internal companion object {
        const val CONNECT_PREPARE_TIMEOUT_MS=120_000L
        const val WATCH_BOUND_TIMEOUT_MS=120_000L
    }
}

/** Only watch credentials are transported; phone login/app-access tokens are never included. */
internal fun configureMessage(owner:PairingOwner,qr:WatchPairingQr,token:WatchDeviceToken):JSONObject = JSONObject()
    .put("action","configure").put("account",JSONObject().put("userId",owner.userId).put("phoneMasked",owner.phoneMasked).put("installId",qr.installId)
        .put("watchAccessToken",token.accessToken).put("accessTokenExpiresAt",token.accessTokenExpiresAtMillis)
        .put("watchRefreshToken",token.refreshToken).put("refreshTokenExpiresAt",token.refreshTokenExpiresAtMillis)
        .put("backendBaseUrl",owner.backendBaseUrl).put("posthogHost","").put("posthogProjectApiKey",""))
    .put("entitlement",JSONObject().put("plan",token.entitlement.plan).put("active",token.entitlement.active).put("expiresAt",token.entitlement.expiresAtMillis).put("features",JSONArray(token.entitlement.features)))
    .put("telemetry",JSONObject().put("anonymousEnabled",false).put("diagnosticsEnabled",false).put("sampleRate",1))
