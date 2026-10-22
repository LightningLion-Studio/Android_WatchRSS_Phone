@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WatchPairingViewModelTest {
    private val d=PairingTestData
    private class Ports:WatchPairingPorts {
        val data=PairingTestData
        override val changes=MutableSharedFlow<Unit>(extraBufferCapacity=10)
        var current:PairingOwner?=data.owner
        var hasKeys=true;var cloudFailure:Exception?=null;var keyReceiptFailure=false;var finishFailure=false
        var issueCalls=0;var cloudCalls=0;var opens=0;var closes=0;var invalidated=false
        var configureGate:CompletableDeferred<Unit>?=null
        val calls=mutableListOf<String>();val messages=mutableListOf<JSONObject>()
        override fun currentOwner()=current
        override suspend fun prepareCloud(owner:PairingOwner):PairingCloudPreparation {calls+="prepareCloud";return if(hasKeys)PairingCloudPreparation.Ready(data.epoch)else PairingCloudPreparation.NeedsKey(data.epoch,true)}
        override fun openChannel(qr:WatchPairingQr,owner:PairingOwner):WatchPairingChannel {opens++;return object:WatchPairingChannel {
            override suspend fun connect(){calls+="claim"}
            override suspend fun request(message:JSONObject):JSONObject {
                val action=message.getString("action");calls+=action;messages+=JSONObject(message.toString())
                return when(action) {
                    "prepare"->JSONObject().put("success",true).put("userId",data.user).put("deviceId",data.device).put("publicKeySpki",data.spki)
                    "configure"->{configureGate?.await();JSONObject().put("success",true).put("bound",true).put("userId",data.user).put("deviceId",data.device)}
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
    private fun TestScope.vm(p:Ports)=WatchPairingViewModel(p,backgroundScope,{d.now})
    private fun TestScope.scan(vm:WatchPairingViewModel) {vm.startScan(true);runCurrent();assertTrue(vm.state.value is WatchPairingState.Scanning);vm.onQrDecoded(d.raw);runCurrent();assertTrue(vm.state.value is WatchPairingState.Confirming)}
    @Test fun constructionNeverRequestsCameraAndNeedsKeyAutomaticallyResumesOnlyAfterRecovery()=runTest {
        val p=Ports();p.hasKeys=false;val vm=vm(p);runCurrent()
        assertEquals(WatchPairingState.Idle,vm.state.value);assertTrue(p.calls.isEmpty())
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
        vm.cancel();vm.startScan(true);runCurrent();oldCallback(d.raw);runCurrent()
        assertTrue(vm.state.value is WatchPairingState.Scanning);assertEquals(0,p.opens)
        vm.qrCallback()(d.raw);runCurrent();assertTrue(vm.state.value is WatchPairingState.Confirming);assertEquals(1,p.opens);vm.cancel()
    }
}
