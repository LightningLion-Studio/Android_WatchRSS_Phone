package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.cloud.*
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

internal object PairingTestData {
    val fixture=JSONObject(requireNotNull(javaClass.getResource("/pairing/mini-online-pairing.json")).readText())
    val now=fixture.getLong("now")
    val user=fixture.getString("userId")
    val epoch=fixture.getString("epoch")
    val raw=fixture.getString("qr")
    val device=fixture.getJSONObject("descriptor").getString("deviceId")
    val install=fixture.getJSONObject("descriptor").getString("installId")
    val spki=JSONObject(fixture.getJSONObject("watch").getString("plaintext")).getString("publicKeySpki")
    val owner=PairingOwner(user,"https://test.invalid",epoch,"138****8000")
    val privateKey:PrivateKey=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(fixture.getString("watchPrivatePkcs8"))))
    fun qr()=WatchPairingQr.parse(raw,now)
    fun keys(count:Int=1)=(1..count).associateWith { ByteArray(32) { 7 } }
    fun bootstrap(count:Int=1):CloudBootstrap {
        val keys=keys(count)
        val commitments=keys.map { (v,k) -> val c=CloudProtocolV2.commitment(k,user,epoch,v)
            CloudKeyCommitment(v,c,if(v==1)null else CloudProtocolV2.rotationProof(keys.getValue(v-1),user,epoch,v,c)) }
        return CloudBootstrap(CloudMemberState("free",true,true,true,15*1024*1024,0,0,30,null,null),
            listOf(RegisteredCloudDevice(device,"watch","Watch",spki,count,0,null,watchSelfRegisteredAt="2026-10-21T00:00:00Z")),emptyList(),epoch,keyCommitments=commitments)
    }
    fun grant(qr:WatchPairingQr=qr())=buildPairingCloudGrant(user,"phone",bootstrap(),qr,spki,keys())
}
