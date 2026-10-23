@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchQrRequest
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchQrNotOpenedReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

class WatchPairingViewModelTest {
    private val d=PairingTestData
    private class Ports:WatchPairingPorts {
        val data=PairingTestData
        override val changes=MutableSharedFlow<Unit>(extraBufferCapacity=10)
        var current:PairingOwner?=data.owner
        var hasKeys=true;var cloudFailure:Exception?=null;var keyReceiptFailure=false;var finishFailure=false
        var issueCalls=0;var cloudCalls=0;var opens=0;var closes=0;var invalidated=false
        var claimGate:CompletableDeferred<Unit>?=null
        var prepareGate:CompletableDeferred<Unit>?=null
        var prepareReceipt:JSONObject?=null
        var ignorePrepareCancellation=false
        var channelFactory:((WatchPairingQr)->WatchPairingChannel)?=null
        var configureGate:CompletableDeferred<Unit>?=null
        var configureFailure:Exception?=null
        var configureReceipt:JSONObject?=null
        var qrRequests=0;var qrResult:WatchQrRequest=WatchQrRequest.Opened;var qrFailure:Exception?=null
        var qrGate:CompletableDeferred<WatchQrRequest>?=null;var ignoreQrCancellation=false
        val calls=mutableListOf<String>();val messages=mutableListOf<JSONObject>()
        override fun currentOwner()=current
        override suspend fun requestWatchQr():WatchQrRequest {
            qrRequests++;qrFailure?.let {throw it}
            val gate=qrGate?:return qrResult
            return if(ignoreQrCancellation)withContext(NonCancellable){gate.await()}else gate.await()
        }
        override suspend fun prepareCloud(owner:PairingOwner):PairingCloudPreparation {calls+="prepareCloud";return if(hasKeys)PairingCloudPreparation.Ready(data.epoch)else PairingCloudPreparation.NeedsKey(data.epoch,true)}
        override fun openChannel(qr:WatchPairingQr,owner:PairingOwner):WatchPairingChannel {opens++;channelFactory?.let { return it(qr) };return object:WatchPairingChannel {
            override suspend fun connect(){calls+="claim";claimGate?.await()}
            override suspend fun request(message:JSONObject):JSONObject {
                val action=message.getString("action");calls+=action;messages+=JSONObject(message.toString())
                return when(action) {
                    "prepare"->{
                        if(ignorePrepareCancellation)withContext(NonCancellable){prepareGate?.await()}else prepareGate?.await()
                        prepareReceipt?:JSONObject().put("success",true).put("userId",data.user).put("deviceId",data.device).put("installId",data.install).put("publicKeySpki",data.spki)
                    }
                    "configure"->{configureGate?.await();configureFailure?.let {throw it};configureReceipt?:JSONObject().put("success",true).put("bound",true).put("userId",data.user).put("deviceId",data.device)}
                    "key"->{if(keyReceiptFailure)throw IOException("key response lost");JSONObject().put("success",true).put("cloudReady",true).put("keyVersion",1).put("deviceId",data.device)}
                    "finish"->{if(finishFailure)throw IOException("finish response lost");JSONObject().put("success",true)}
                    else->error("unexpected action")
                }
            }
            override fun invalidate(){invalidated=true}
            override suspend fun close(){closes++;qr.destroy()}
        }}
        override suspend fun issueCredentials(qr:WatchPairingQr,owner:PairingOwner):WatchDeviceToken {calls+="issue";issueCalls++;return WatchDeviceToken("watch-access",data.now+100000,"watch-refresh",data.now+200000,WatchEntitlementSnapshot())}
        override suspend fun grantCloud(qr:WatchPairingQr,preparedSpki:String,owner:PairingOwner):PairingCloudGrant {calls+="grant";cloudCalls++;cloudFailure?.let{throw it};return data.grant(qr)}
    }
    private fun TestScope.vm(p:Ports)=WatchPairingViewModel(p,backgroundScope,{d.now},{testScheduler.currentTime})
    private fun TestScope.scan(vm:WatchPairingViewModel) {vm.onPageEntered();vm.startScan(true);runCurrent();assertTrue(vm.state.value is WatchPairingState.Scanning);vm.onQrDecoded(d.raw);runCurrent();assertTrue(vm.state.value is WatchPairingState.Confirming)}
    @Test fun phoneMayConfirmBeforeClaimAndPrepareButBothMustArriveBeforeIssuance()=runTest {
        val p=Ports();p.claimGate=CompletableDeferred();p.prepareGate=CompletableDeferred();p.configureGate=CompletableDeferred()
        val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
        val local=vm.state.value as WatchPairingState.Confirming.Phone
        assertEquals(d.fixture.getString("sas"),local.sas);assertFalse(p.calls.contains("prepare"))
        vm.confirmSas(true);runCurrent()
        assertEquals(WatchPairingState.WaitingForPrepareAfterPhone(local.sas),vm.state.value)
        p.claimGate!!.complete(Unit);runCurrent()
        assertEquals(listOf("prepareCloud","claim","prepare"),p.calls)
        assertEquals(0,p.issueCalls);assertEquals(0,p.cloudCalls)
        p.prepareGate!!.complete(Unit);runCurrent()
        val waiting=vm.state.value as WatchPairingState.WaitingWatchConfirmAfterPhone
        assertEquals(local.sas,waiting.sas);assertEquals("请在手表上点一致，绑定",waiting.message)
        assertEquals(1,p.issueCalls);assertEquals(1,p.calls.count {it=="configure"});assertEquals(0,p.cloudCalls)
        repeat(3){vm.confirmSas(true);runCurrent()};assertEquals(1,p.issueCalls)
        p.configureGate!!.complete(Unit);runCurrent()
        assertEquals(WatchPairingState.Done(true,true),vm.state.value);assertEquals(1,p.issueCalls);vm.cancel()
    }
    @Test fun prepareAndWatchMayConfirmFirstButPhoneClickStillGatesIssuanceAndConfigure()=runTest {
        val p=Ports();p.prepareGate=CompletableDeferred();p.configureGate=CompletableDeferred()
        val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
        val local=vm.state.value as WatchPairingState.Confirming.Phone
        // R43 watch confirms on its own screen and remembers the decision before configure exists.
        p.configureGate!!.complete(Unit);p.prepareGate!!.complete(Unit);runCurrent()
        assertEquals(WatchPairingState.Confirming(local.sas),vm.state.value)
        assertEquals(0,p.issueCalls);assertEquals(0,p.calls.count{it=="configure"});assertEquals(0,p.cloudCalls)
        vm.confirmSas(true);runCurrent()
        assertEquals(WatchPairingState.Done(true,true),vm.state.value)
        assertEquals(1,p.issueCalls);assertEquals(1,p.calls.count{it=="configure"});vm.cancel()
    }
    @Test fun realEncryptedRelayCanPollNullWhilePhoneAlreadyOffersItsLocalSas()=runTest {
        val p=Ports();var watchConfirmed=false;var polls=0;var prepares=0;var claims=0
        p.channelFactory={qr -> WatchPairingRelay(qr,object:PairingHttp {
            override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject=when {
                path.endsWith("/claim")->{claims++;JSONObject().put("claimed",true)}
                path.endsWith("/request")->{
                    prepares++
                    val envelope=JSONObject(body!!)
                    val request=PairingWireCodec.open(qr,"phone",0,envelope.getJSONObject("wire"))
                    assertEquals("prepare",request.getString("action"));assertEquals(d.user,request.getString("userId"))
                    assertEquals(d.fixture.getString("sas"),PairingWireCodec.sas(qr,request.getString("userId")))
                    JSONObject().put("accepted",true)
                }
                path.contains("/response?")->{
                    polls++
                    JSONObject().put("wire",if(!watchConfirmed)JSONObject.NULL else PairingWireCodec.seal(qr,"watch",0,
                        JSONObject().put("success",true).put("userId",d.user).put("deviceId",d.device).put("publicKeySpki",d.spki)))
                }
                else->JSONObject().put("closed",true)
            }
        },{},{true},{d.now+testScheduler.currentTime}) }
        val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
        val waiting=vm.state.value as WatchPairingState.Confirming.Phone
        assertEquals(d.fixture.getString("sas"),waiting.sas)
        assertEquals(1,claims);assertEquals(1,prepares);assertEquals(1,polls)
        advanceTimeBy(3600);runCurrent();assertTrue(polls>=4)
        assertEquals(waiting,vm.state.value);assertEquals(0,p.issueCalls)
        watchConfirmed=true;advanceTimeBy(1200);runCurrent()
        assertEquals(WatchPairingState.Confirming(waiting.sas),vm.state.value)
        assertEquals(1,prepares);assertEquals(0,p.issueCalls);vm.cancel();runCurrent()
    }
    @Test fun r43EncryptedRelayAllowsEitherConfirmationOrderAndWatchRejectionNeverBecomesBound()=runTest {
        for(order in listOf("phone-first","watch-first","watch-rejects")) {
            val p=Ports();var watchConfirmed=false;var watchRejected=false;var savedAccount=false
            val requests=linkedMapOf<Int,JSONObject>();var polls=0;var closes=0
            p.channelFactory={qr -> WatchPairingRelay(qr,object:PairingHttp {
                override suspend fun call(method:String,path:String,body:String?,capability:String?,timeoutMillis:Long):JSONObject=when {
                    path.endsWith("/claim")->JSONObject().put("claimed",true)
                    path.endsWith("/request")->{
                        val encrypted=JSONObject(body!!);val seq=encrypted.getInt("seq")
                        val plain=PairingWireCodec.open(qr,"phone",seq,encrypted.getJSONObject("wire"))
                        assertFalse("one accepted request per sequence",requests.containsKey(seq));requests[seq]=plain
                        JSONObject().put("accepted",true)
                    }
                    path.contains("/response?")->{
                        val seq=path.substringAfter("seq=").toInt();val request=requests.getValue(seq)
                        val reply=when(request.getString("action")) {
                            // R43 prepare does not wait for confirmation or carry any account credentials.
                            "prepare"->JSONObject().put("success",true).put("userId",d.user).put("deviceId",d.device).put("installId",d.install).put("publicKeySpki",d.spki)
                            "configure"->{
                                polls++
                                assertEquals("watch-access",request.getJSONObject("account").getString("watchAccessToken"))
                                when {
                                    watchRejected->JSONObject().put("success",false)
                                    !watchConfirmed->null
                                    else->{savedAccount=true;JSONObject().put("success",true).put("bound",true).put("userId",d.user).put("deviceId",d.device)}
                                }
                            }
                            "key"->{assertTrue(savedAccount);JSONObject().put("success",true).put("cloudReady",true).put("keyVersion",1).put("deviceId",d.device)}
                            "finish"->JSONObject().put("success",true)
                            else->error("unexpected action")
                        }
                        JSONObject().put("wire",if(reply==null)JSONObject.NULL else PairingWireCodec.seal(qr,"watch",seq,reply))
                    }
                    else->{closes++;JSONObject().put("closed",true)}
                }
            },{},{true},{d.now+testScheduler.currentTime}) }
            val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
            assertEquals(WatchPairingState.Confirming(d.fixture.getString("sas")),vm.state.value)
            assertEquals(listOf("prepare"),requests.values.map{it.getString("action")})
            assertFalse(savedAccount);assertEquals(0,p.issueCalls)
            if(order=="watch-first")watchConfirmed=true
            vm.confirmSas(true);runCurrent()
            if(order!="watch-first") {
                assertTrue(vm.state.value is WatchPairingState.WaitingWatchConfirmAfterPhone)
                assertFalse(savedAccount);assertEquals(0,p.cloudCalls);assertEquals(1,p.issueCalls)
                advanceTimeBy(2400);runCurrent();assertTrue(polls>=3)
                if(order=="watch-rejects")watchRejected=true else watchConfirmed=true
                advanceTimeBy(1200);runCurrent()
            }
            if(order=="watch-rejects") {
                assertFalse(savedAccount);assertFalse((vm.state.value as WatchPairingState.Failed).canRetry)
                assertEquals(listOf("prepare","configure"),requests.values.map{it.getString("action")})
                assertEquals(0,p.cloudCalls)
            } else {
                assertEquals(WatchPairingState.Done(true,true),vm.state.value);assertTrue(savedAccount)
                assertEquals(listOf("prepare","configure","key","finish"),requests.values.map{it.getString("action")})
            }
            assertEquals(1,closes);assertEquals(1,p.issueCalls);vm.cancel();runCurrent()
        }
    }
    @Test fun claimAndPrepareEachHaveATwoMinuteErrorExitAndTimeoutCanRetrySameSession()=runTest {
        for(stage in listOf("claim","prepare")) {
            val p=Ports();val gate=CompletableDeferred<Unit>()
            if(stage=="claim")p.claimGate=gate else p.prepareGate=gate
            val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
            advanceTimeBy(119_999);runCurrent()
            assertFalse(vm.state.value is WatchPairingState.Failed)
            advanceTimeBy(1);runCurrent()
            val failed=vm.state.value as WatchPairingState.Failed
            assertTrue(failed.message.contains("超时"));assertTrue(failed.canRetry);assertEquals(0,p.issueCalls)
            gate.complete(Unit);vm.retry();runCurrent()
            assertTrue(vm.state.value is WatchPairingState.Confirming.Phone)
            assertEquals(1,p.opens);assertEquals(0,p.issueCalls);vm.cancel()
        }
    }
    @Test fun cancellationAndOwnerChangeDuringWatchConfirmationRejectLateReceipts()=runTest {
        for(change in listOf("cancel","user","logout","backend","epoch")) {
            val p=Ports();p.prepareGate=CompletableDeferred();p.ignorePrepareCancellation=true
            val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
            assertTrue(vm.state.value is WatchPairingState.Confirming.Phone)
            if(change=="cancel")vm.cancel()else {
                p.current=when(change){"user"->d.owner.copy(userId="other");"backend"->d.owner.copy(backendBaseUrl="https://other.invalid");"epoch"->d.owner.copy(epoch="new");else->null}
                p.changes.emit(Unit)
            }
            runCurrent();p.prepareGate!!.complete(Unit);runCurrent()
            if(change=="cancel")assertEquals(WatchPairingState.Idle,vm.state.value)
            else assertTrue(vm.state.value is WatchPairingState.Failed)
            assertEquals(0,p.issueCalls);assertTrue(p.invalidated);vm.cancel()
        }
    }
    @Test fun rejectionOrMismatchingPrepareIdentityAndSasNeverAllowsPhoneConfirmation()=runTest {
        for(kind in listOf("user","device","install","sas","sas-null","sas-number","success","spki")) {
            val p=Ports();p.prepareGate=CompletableDeferred()
            p.prepareReceipt=JSONObject().put("success",true).put("userId",d.user).put("deviceId",d.device).put("publicKeySpki",d.spki).apply {
                when(kind) {
                    "user"->put("userId","other");"device"->put("deviceId","other");"install"->put("installId","other");"sas"->put("sas","00000000")
                    "sas-null"->put("sas",JSONObject.NULL);"sas-number"->put("sas",61099432)
                    "success"->put("success",false);else->put("publicKeySpki","broken")
                }
            }
            val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
            assertTrue(vm.state.value is WatchPairingState.Confirming.Phone)
            vm.confirmSas(true);runCurrent();assertEquals(0,p.issueCalls)
            p.prepareGate!!.complete(Unit);runCurrent()
            assertTrue(vm.state.value is WatchPairingState.Failed)
            vm.confirmSas(true);runCurrent();assertEquals(0,p.issueCalls);assertEquals(0,p.cloudCalls);vm.cancel()
        }
    }
    @Test fun optionalEchoedSasMustMatchLocalCodeAndDisagreeingWhileWaitingCancels()=runTest {
        val p=Ports();p.prepareGate=CompletableDeferred()
        p.prepareReceipt=JSONObject().put("success",true).put("userId",d.user).put("deviceId",d.device).put("publicKeySpki",d.spki).put("sas",d.fixture.getString("sas"))
        val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
        vm.confirmSas(false);runCurrent();assertTrue(vm.state.value is WatchPairingState.Failed)
        p.prepareGate!!.complete(Unit);runCurrent();assertEquals(0,p.issueCalls);assertTrue(p.invalidated)
        vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Confirming.Phone);vm.cancel()
    }
    @Test fun phoneConfirmedPrepareTimeoutRetriesPrepareRatherThanIssuingWithoutAReceipt()=runTest {
        val p=Ports();p.prepareGate=CompletableDeferred();val vm=vm(p)
        vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent();vm.confirmSas(true);runCurrent()
        advanceTimeBy(120_000);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Failed);assertEquals(0,p.issueCalls)
        vm.retry();runCurrent()
        assertTrue(vm.state.value is WatchPairingState.WaitingForPrepareAfterPhone)
        assertEquals(0,p.issueCalls);assertEquals(1,p.opens)
        p.prepareGate!!.complete(Unit);runCurrent()
        assertEquals(WatchPairingState.Done(true,true),vm.state.value);assertEquals(1,p.issueCalls)
        assertEquals(2,p.calls.count {it=="prepare"});vm.cancel()
    }
    @Test fun configureWaitsForWatchBoundWithTwoMinuteTimeoutAndReusesCredentialsOnRetry()=runTest {
        val p=Ports();p.configureGate=CompletableDeferred();val vm=vm(p);scan(vm)
        vm.confirmSas(true);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.WaitingWatchConfirmAfterPhone)
        assertEquals(1,p.issueCalls);assertEquals(0,p.cloudCalls)
        advanceTimeBy(119_999);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.WaitingWatchConfirmAfterPhone)
        advanceTimeBy(1);runCurrent()
        val failed=vm.state.value as WatchPairingState.Failed
        assertTrue(failed.message.contains("手表绑定确认超时"));assertTrue(failed.canRetry);assertEquals(0,p.cloudCalls)
        p.configureGate!!.complete(Unit);vm.retry();runCurrent()
        assertEquals(WatchPairingState.Done(true,true),vm.state.value);assertEquals(1,p.issueCalls)
        val messages=p.messages.filter {it.getString("action")=="configure"}
        assertEquals(2,messages.size);assertEquals(messages[0].toString(),messages[1].toString());vm.cancel()
    }
    @Test fun eitherScreenMayRejectBeforeOrAfterPhoneConfirmAndNoKeyIsDelivered()=runTest {
        for(stage in listOf("before-prepare","after-phone","watch-bound")) {
            val p=Ports();if(stage!="watch-bound")p.prepareGate=CompletableDeferred()else p.configureGate=CompletableDeferred()
            val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
            if(stage!="before-prepare")vm.confirmSas(true)
            runCurrent();vm.confirmSas(false);runCurrent()
            val failed=vm.state.value as WatchPairingState.Failed
            assertFalse(failed.canRetry);assertTrue(p.invalidated);assertEquals(0,p.cloudCalls)
            p.prepareGate?.complete(Unit);p.configureGate?.complete(Unit);runCurrent()
            assertEquals(failed,vm.state.value);assertEquals(0,p.cloudCalls);vm.cancel()
        }
        for(error in listOf(PairingException("watch_rejected","手表拒绝绑定"),
            PairingHttpException(404,"pairing_invalid"),PairingHttpException(410,"pairing_expired"))) {
            val p=Ports();p.configureFailure=error
            val vm=vm(p);scan(vm);vm.confirmSas(true);runCurrent()
            val rejected=vm.state.value as WatchPairingState.Failed
            assertFalse("watch rejection must end the session",rejected.canRetry)
            assertTrue("watch rejection invalidates the channel",p.invalidated);assertEquals(1,p.closes)
            assertEquals(0,p.cloudCalls);assertEquals(0,p.calls.count{it=="key"});vm.cancel()
        }
    }
    @Test fun ownerChangeOrCancelWhilePhoneAwaitsPrepareCannotTriggerIssuanceFromALateReceipt()=runTest {
        for(change in listOf("cancel","user","logout","backend","epoch")) {
            val p=Ports();p.prepareGate=CompletableDeferred();p.ignorePrepareCancellation=true
            val vm=vm(p);vm.startScan(true);runCurrent();vm.onQrDecoded(d.raw);runCurrent()
            vm.confirmSas(true);runCurrent();assertTrue(vm.state.value is WatchPairingState.WaitingForPrepareAfterPhone)
            if(change=="cancel")vm.cancel()else {
                p.current=when(change){"user"->d.owner.copy(userId="other");"backend"->d.owner.copy(backendBaseUrl="https://other.invalid");"epoch"->d.owner.copy(epoch="new");else->null}
                p.changes.emit(Unit)
            }
            runCurrent();p.prepareGate!!.complete(Unit);runCurrent()
            if(change=="cancel")assertEquals(WatchPairingState.Idle,vm.state.value)else assertTrue(vm.state.value is WatchPairingState.Failed)
            assertEquals(0,p.issueCalls);assertEquals(0,p.cloudCalls);vm.cancel()
        }
    }
    @Test fun constructionNeverRequestsCameraAndNeedsKeyAutomaticallyResumesOnlyAfterRecovery()=runTest {
        val p=Ports();p.hasKeys=false;val vm=vm(p);runCurrent()
        assertEquals(WatchPairingState.Idle,vm.state.value);assertTrue(p.calls.isEmpty())
        assertEquals(0,p.qrRequests);assertNull(vm.watchQrRequest.value)
        vm.startScan();runCurrent();assertEquals(WatchPairingState.NeedsKey(true),vm.state.value);assertEquals(0,p.opens)
        p.hasKeys=true;p.changes.emit(Unit);runCurrent()
        assertEquals(WatchPairingState.Scanning(PairingCameraPermission.REQUEST_REQUIRED),vm.state.value);assertEquals(0,p.opens)
        vm.onCameraPermissionResult(false,false);assertEquals(WatchPairingState.Scanning(PairingCameraPermission.BLOCKED),vm.state.value)
        vm.onQrDecoded(d.raw);runCurrent();assertEquals(0,p.opens)
        vm.retry();assertEquals(WatchPairingState.Scanning(PairingCameraPermission.REQUEST_REQUIRED),vm.state.value)
        vm.onCameraPermissionResult(true);vm.onQrDecoded(d.raw);runCurrent();assertEquals(1,p.opens);vm.cancel()
    }
    @Test fun successfulFlowRequiresSasAndWatchKeyReceiptBeforeDoneCloudReady()=runTest {
        val p=Ports();val vm=vm(p);scan(vm)
        assertEquals(d.fixture.getString("sas"),(vm.state.value as WatchPairingState.Confirming).sas)
        assertEquals(0,p.issueCalls);assertEquals(0,p.cloudCalls)
        vm.confirmSas(true);runCurrent()
        assertEquals(WatchPairingState.Done(true,true),vm.state.value)
        assertEquals(listOf("prepareCloud","claim","prepare","issue","configure","grant","key","finish"),p.calls)
        assertEquals(1,p.closes)
        val account=p.messages.first{it.getString("action")=="configure"}.getJSONObject("account")
        assertEquals(d.install,account.getString("installId"));assertEquals("watch-access",account.getString("watchAccessToken"))
        assertEquals("watch-refresh",account.getString("watchRefreshToken"));assertEquals("https://test.invalid",account.getString("backendBaseUrl"))
        assertFalse(account.has("accessToken"));assertFalse(account.has("deviceAccessToken"));vm.cancel()
    }
    @Test fun sasMismatchCancelsBeforeIssuingCredentialsOrChangingCloudAuthorization()=runTest {
        val p=Ports();val vm=vm(p);scan(vm);vm.confirmSas(false);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Failed);assertEquals(0,p.issueCalls);assertEquals(0,p.cloudCalls);assertTrue(p.invalidated);assertEquals(1,p.closes)
    }
    @Test fun r28MissingSelfRegistrationKeepsAccountBoundButNeverClaimsCloudReady()=runTest {
        val p=Ports();p.cloudFailure=PairingException("watch_account_confirmation_required","手表尚未自行登记云设备")
        val vm=vm(p);scan(vm);vm.confirmSas(true);runCurrent()
        val done=vm.state.value as WatchPairingState.Done;assertTrue(done.bound);assertFalse(done.cloudReady);assertTrue(done.cloudError!!.contains("自行登记"));assertTrue(done.canRetry)
        assertEquals(0,p.calls.count{it=="key"});assertEquals(0,p.calls.count{it=="finish"})
        p.cloudFailure=null;vm.retry();runCurrent();assertEquals(WatchPairingState.Done(true,true),vm.state.value)
        assertEquals(1,p.issueCalls);assertEquals(1,p.calls.count{it=="configure"});vm.cancel()
    }
    @Test fun ambiguousKeyReceiptRetryReusesGrantPayloadAndDoesNotReissueCredentials()=runTest {
        val p=Ports();p.keyReceiptFailure=true;val vm=vm(p);scan(vm);vm.confirmSas(true);runCurrent()
        val done=vm.state.value as WatchPairingState.Done;assertTrue(done.bound);assertFalse(done.cloudReady)
        p.keyReceiptFailure=false;vm.retry();runCurrent();assertTrue((vm.state.value as WatchPairingState.Done).cloudReady)
        val keyMessages=p.messages.filter{it.getString("action")=="key"};assertEquals(2,keyMessages.size)
        assertEquals(keyMessages[0].toString(),keyMessages[1].toString());assertEquals(1,p.cloudCalls);assertEquals(1,p.issueCalls);vm.cancel()
    }
    @Test fun finishWarningKeepsBoundAndCloudReadyAndRetryDoesNotRepeatBindingOrKeys()=runTest {
        val p=Ports();p.finishFailure=true;val vm=vm(p);scan(vm);vm.confirmSas(true);runCurrent()
        val done=vm.state.value as WatchPairingState.Done;assertTrue(done.bound);assertTrue(done.cloudReady);assertNull(done.cloudError);assertNotNull(done.finishError)
        p.finishFailure=false;vm.retry();runCurrent();assertEquals(WatchPairingState.Done(true,true),vm.state.value)
        assertEquals(1,p.calls.count{it=="configure"});assertEquals(1,p.calls.count{it=="key"});assertEquals(2,p.calls.count{it=="finish"});vm.cancel()
    }
    @Test fun accountLogoutBackendOrEpochChangesSuppressLateBoundReceipts()=runTest {
        for(change in listOf("user","logout","backend","epoch")) {
            val p=Ports();p.configureGate=CompletableDeferred();val vm=vm(p);scan(vm);vm.confirmSas(true);runCurrent()
            assertTrue(vm.state.value is WatchPairingState.Configuring)
            p.current=when(change){"user"->d.owner.copy(userId="other");"backend"->d.owner.copy(backendBaseUrl="https://other.invalid");"epoch"->d.owner.copy(epoch="new");else->null}
            p.changes.emit(Unit);runCurrent();p.configureGate!!.complete(Unit);runCurrent()
            assertTrue(vm.state.value is WatchPairingState.Failed);assertEquals(0,p.cloudCalls);assertEquals(0,p.calls.count{it=="key"});assertTrue(p.invalidated);vm.cancel()
        }
    }
    @Test fun sameAccountRefreshDoesNotInvalidateSasOrReplaceItsPairingSession()=runTest {
        val p=Ports();val vm=vm(p);scan(vm)
        p.current=d.owner.copy(phoneMasked="new mask");p.changes.emit(Unit);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Confirming);vm.confirmSas(true);runCurrent();assertTrue((vm.state.value as WatchPairingState.Done).cloudReady);assertEquals(1,p.opens);vm.cancel()
    }
    @Test fun leavingPageCancelsNeedsKeyResumeAndLateConfigureCompletion()=runTest {
        val p=Ports();p.hasKeys=false;val vm=vm(p);vm.startScan();runCurrent();vm.cancel();p.hasKeys=true;p.changes.emit(Unit);runCurrent()
        assertEquals(WatchPairingState.Idle,vm.state.value);assertEquals(0,p.opens)
        p.configureGate=CompletableDeferred();scan(vm);vm.confirmSas(true);runCurrent();vm.cancel();p.configureGate!!.complete(Unit);runCurrent()
        assertEquals(WatchPairingState.Idle,vm.state.value);assertEquals(0,p.cloudCalls)
    }
    @Test fun queuedCameraCallbackFromAbandonedScanCannotClaimTheNextScan()=runTest {
        val p=Ports();val vm=vm(p);vm.startScan(true);runCurrent();val oldCallback=vm.qrCallback()
        val queued=mutableListOf<Runnable>();var closes=0
        val image=Proxy.newProxyInstance(ImageProxy::class.java.classLoader,arrayOf(ImageProxy::class.java)){_,method,_->if(method.name=="close")closes++;null} as ImageProxy
        PairingQrAnalyzer(Executor{queued+=it},oldCallback,decode={d.raw}).analyze(image)
        assertEquals(1,closes);assertEquals(1,queued.size)
        vm.cancel();vm.startScan(true);runCurrent();queued.single().run();runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Scanning);assertEquals(0,p.opens)
        vm.qrCallback()(d.raw);runCurrent();assertTrue(vm.state.value is WatchPairingState.Confirming);assertEquals(1,p.opens);vm.cancel()
    }
    @Test fun automaticQrRequestIsParallelAndNeverBlocksScanningOrCompletingThePairing()=runTest {
        val p=Ports();p.qrGate=CompletableDeferred();val vm=vm(p)
        vm.onPageEntered();assertEquals(WatchQrRequest.Sending,vm.watchQrRequest.value);vm.startScan(true);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Scanning);assertEquals(WatchQrRequest.Sending,vm.watchQrRequest.value)
        vm.onQrDecoded(d.raw);runCurrent();vm.confirmSas(true);runCurrent()
        assertEquals(WatchPairingState.Done(true,true),vm.state.value);assertEquals(1,p.qrRequests)
        p.qrGate!!.complete(WatchQrRequest.Opened);runCurrent();assertEquals(WatchQrRequest.Opened,vm.watchQrRequest.value);vm.cancel()
    }
    @Test fun everyNotOpenedReasonLeavesTheSameScanningAndPairingFlowUsable()=runTest {
        for(reason in WatchQrNotOpenedReason.entries) {
            val p=Ports();p.qrResult=WatchQrRequest.NotOpened(reason);val vm=vm(p)
            scan(vm);assertEquals(WatchQrRequest.NotOpened(reason),vm.watchQrRequest.value)
            vm.confirmSas(true);runCurrent();assertEquals(WatchPairingState.Done(true,true),vm.state.value);assertEquals(1,p.qrRequests);vm.cancel()
        }
    }
    @Test fun optionalQrPortExceptionsNeverFailCloudPreparationOrScan()=runTest {
        val p=Ports();p.qrFailure=IOException("raw platform details");val vm=vm(p)
        vm.onPageEntered();vm.startScan();runCurrent();assertTrue(vm.state.value is WatchPairingState.Scanning)
        assertEquals(WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE),vm.watchQrRequest.value);vm.cancel()
    }
    @Test fun pendingQrPortTimesOutAtSixSecondsWithoutInterruptingTheScanner()=runTest {
        val p=Ports();p.qrGate=CompletableDeferred();val vm=vm(p);vm.onPageEntered();vm.startScan(true);runCurrent()
        advanceTimeBy(5_999);runCurrent();assertEquals(WatchQrRequest.Sending,vm.watchQrRequest.value)
        advanceTimeBy(1);runCurrent();assertEquals(WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE),vm.watchQrRequest.value)
        assertTrue(vm.state.value is WatchPairingState.Scanning);assertEquals(1,p.qrRequests);vm.cancel()
    }
    @Test fun recoveringKeysAndRetryingCameraPermissionDoNotResendTheAutomaticRequest()=runTest {
        val p=Ports();p.hasKeys=false;val vm=vm(p);vm.onPageEntered();vm.startScan();runCurrent()
        assertTrue(vm.state.value is WatchPairingState.NeedsKey);assertEquals(1,p.qrRequests)
        vm.retry();runCurrent();p.hasKeys=true;p.changes.emit(Unit);runCurrent();vm.retry();runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Scanning);assertEquals(1,p.qrRequests);vm.cancel()
    }
    @Test fun cancelOrOwnerChangeSuppressesLateQrReceiptsButStartingScanKeepsThePageRequest()=runTest {
        for(change in listOf("cancel","owner","restart")) {
            val p=Ports();val old=CompletableDeferred<WatchQrRequest>();p.qrGate=old;p.ignoreQrCancellation=true
            val vm=vm(p);vm.onPageEntered();vm.startScan(true);runCurrent()
            when(change) {
                "owner"->{p.current=d.owner.copy(userId="other");p.changes.emit(Unit)}
                "restart"->{p.qrGate=null;p.qrResult=WatchQrRequest.NotOpened(WatchQrNotOpenedReason.BLUETOOTH_OFF);vm.startScan(true)}
                else->vm.cancel()
            }
            runCurrent();old.complete(WatchQrRequest.Opened);runCurrent()
            if(change=="restart") {assertEquals(1,p.qrRequests);assertEquals(WatchQrRequest.Opened,vm.watchQrRequest.value)}
            else assertNull(vm.watchQrRequest.value)
            vm.cancel()
        }
    }
    @Test fun pageEntryIsOncePerRetainedViewModelAndDoesNotStartCameraOrCloudPreparation()=runTest {
        val p=Ports();val vm=vm(p);vm.onPageEntered();runCurrent()
        assertEquals(WatchPairingState.Idle,vm.state.value);assertTrue(p.calls.isEmpty());assertEquals(1,p.qrRequests)
        repeat(3){vm.onPageEntered();runCurrent()};assertEquals(1,p.qrRequests)
        vm.startScan(true);runCurrent();vm.onPageEntered();runCurrent();assertEquals(1,p.qrRequests)
        vm.cancel();vm.onPageEntered();runCurrent();assertEquals(1,p.qrRequests)
    }
    @Test fun failedPageRequestCanRetryOnlyStrictlyAfterTenSecondsAndOpenedRequestsNeverRepeat()=runTest {
        val p=Ports();p.qrResult=WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE);val vm=vm(p)
        vm.onPageEntered();runCurrent();assertFalse(vm.requestWatchQrAgain())
        advanceTimeBy(10_000);assertFalse(vm.requestWatchQrAgain());assertEquals(1,p.qrRequests)
        vm.startScan(true);runCurrent();assertEquals(1,p.qrRequests)
        advanceTimeBy(1);p.qrResult=WatchQrRequest.Opened;vm.startScan(true);runCurrent();assertEquals(2,p.qrRequests)
        advanceTimeBy(20_000);assertFalse(vm.requestWatchQrAgain());vm.startScan(true);runCurrent();assertEquals(2,p.qrRequests);vm.cancel()
    }
    @Test fun explicitRetryButtonUsesTheSameCooldownAndDoesNotPrepareCloudOrRequestCamera()=runTest {
        val p=Ports();p.qrResult=WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE);val vm=vm(p)
        assertFalse(vm.requestWatchQrAgain());vm.onPageEntered();runCurrent();advanceTimeBy(10_001)
        assertTrue(vm.requestWatchQrAgain());assertFalse(vm.requestWatchQrAgain());runCurrent()
        assertEquals(2,p.qrRequests);assertEquals(WatchPairingState.Idle,vm.state.value);assertTrue(p.calls.isEmpty());vm.cancel()
    }
}
