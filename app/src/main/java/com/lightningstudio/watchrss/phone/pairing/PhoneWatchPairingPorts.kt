package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

internal class PhoneWatchPairingPorts(
    private val account:PhoneAccountRepository,private val cloud:PhoneCloudSyncService,
    private val environment:AccountEnvironment,private val deviceAccessToken:()->String?,
    private val refreshDeviceSession:suspend(String,String)->Boolean
):WatchPairingPorts {
    override val changes:Flow<Unit> = merge(account.session.map {Unit},cloud.pairingChanges)
    override fun currentOwner():PairingOwner? = account.session.value?.let {
        PairingOwner(it.userId,cloud.pairingBackendBaseUrl,cloud.pairingEpoch(),it.phoneMasked)
    }
    private fun check(owner:PairingOwner) {
        val current=currentOwner()?:throw PairingInvalidated()
        if(current.userId!=owner.userId || current.backendBaseUrl!=owner.backendBaseUrl || (owner.epoch!=null&&current.epoch!=owner.epoch)) throw PairingInvalidated()
    }
    override suspend fun prepareCloud(owner:PairingOwner):PairingCloudPreparation {
        check(owner);return cloud.prepareWatchPairingCloud(owner.userId).also {check(owner)}
    }
    override fun openChannel(qr:WatchPairingQr,owner:PairingOwner):WatchPairingChannel {
        check(owner)
        val http=PairingRelayHttp(environment,session={check(owner);requireNotNull(account.session.value)},deviceAccessToken,refreshDeviceSession)
        return WatchPairingRelay(qr,http,{check(owner)},{val current=currentOwner();current?.userId==owner.userId&&current.backendBaseUrl==owner.backendBaseUrl})
    }
    override suspend fun issueCredentials(qr:WatchPairingQr,owner:PairingOwner) = run {
        check(owner);account.issuePairingWatchCredentials(qr.deviceId,qr.installId,owner.userId).also {check(owner)}
    }
    override suspend fun grantCloud(qr:WatchPairingQr,preparedSpki:String,owner:PairingOwner):PairingCloudGrant {
        check(owner);return cloud.grantWatchPairingCloud(qr,preparedSpki,requireNotNull(owner.epoch),owner.userId).also {check(owner)}
    }
}
