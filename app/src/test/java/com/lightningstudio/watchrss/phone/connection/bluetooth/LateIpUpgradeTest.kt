package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.connection.ip.*
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class LateIpUpgradeTest {
    private val context=RuntimeEnvironment.getApplication()
    private val client=PhoneBluetoothSyncClient(context,BluetoothDebugLog(context))
    private val outputs=mutableListOf<ByteArrayOutputStream>()
    private val sessions=mutableListOf<PhoneSyncSession>()
    private var socketClosed=false
    private fun response(phase:String,device:String="watch")=JSONObject().put("success",true).put("action",if(phase=="probe")"syncLibrary"else "syncSession").put("phase",phase).put("version",14).put("deviceId",device).put("ipUpgradeAccepted",true).put("supportsDomainFastPathV2",true)
    private fun session(v2:Boolean=true,peer:String="watch"):PhoneSyncSession {
        val wire=ByteArrayOutputStream();listOf(response("probe",peer),response("complete")).forEach{BluetoothSyncProtocol.writeFrame(wire,it)}
        val output=ByteArrayOutputStream().also{outputs+=it}
        return PhoneSyncSession(client,PhoneBluetoothWatchDevice("watch","BT",1,"watch","BT",true),"phone",
            PhoneSyncSession.Transport(wire.toByteArray().inputStream(),output,"rfcomm",false,{socketClosed=true}),true,false,true)
            .also{it.bindingProbe=response("probe").put("supportsDomainFastPathV2",v2);sessions+=it}
    }
    @After fun close(){sessions.forEach{it.close()};PhoneIpSyncSessionRegistry.closeAll()}
    @Test fun aMissReoffersFreshSignedEndpointsOnceAndPromotesOnlyAfterAnAuthenticatedHello()=runBlocking<Unit> {
        val old=session();val waits=mutableListOf<Long>();var calls=0
        val ip=PhoneIpSyncSession("watch","new-hello",IpTransportKind.WIFI_LAN,"192.0.2.1",{}, {})
        val result=client.promoteLateIpSession(old,"refresh",descriptorProvider={assertEquals("watch",it);JSONObject().put("challengeId","fresh").put("endpoints",JSONArray().put(JSONArray(listOf("wlan","192.0.2.1","wifiLan",1))))},awaitSession={id,time->assertEquals("watch",id);waits+=time;if(calls++==0)null else ip})
        try {
            assertNotSame(old,result);assertTrue(socketClosed);assertEquals("ip:wifiLan",result.transport!!.owner)
            assertEquals(2,waits.size);val sent=ByteArrayInputStream(outputs.single().toByteArray());val probe=BluetoothSyncProtocol.readFrame(sent)
            assertEquals("probe",probe.getString("phase"));assertEquals("fresh",probe.getJSONObject("ipEndpointDescriptor").getString("challengeId"))
        }finally{result.close();ip.close()}
    }
    @Test fun aMissingRouteOrAnOlderPeerKeepsRfcommWithoutExtraWaitsOrProtocolChanges()=runBlocking<Unit> {
        for(v2 in listOf(false,true)) {
            var descriptors=0;var waits=0;val old=session(v2)
            val result=client.promoteLateIpSession(old,"no-route",descriptorProvider={descriptors++;JSONObject().put("endpoints",JSONArray())},awaitSession={_,_->waits++;null})
            assertSame(old,result);assertEquals(1,waits);assertEquals(if(v2)1 else 0,descriptors)
            assertFalse(socketClosed);assertEquals(0,outputs.last().size())
        }
    }
    @Test fun refreshedProbeCannotTakeOverAConnectionForAnotherWatch()=runBlocking<Unit> {
        val old=session(peer="other")
        try{client.promoteLateIpSession(old,"wrong-peer",descriptorProvider={JSONObject().put("endpoints",JSONArray().put("test"))},awaitSession={_,_->null});fail("peer substitution accepted")}
        catch(_:IllegalArgumentException){}
    }
}
