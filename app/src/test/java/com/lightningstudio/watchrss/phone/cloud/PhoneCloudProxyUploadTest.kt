package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudProxyUploadTest {
    private val session=PhoneAccountSession("owner","***","session","",Long.MAX_VALUE)
    private val id="11111111-1111-4111-8111-111111111111"
    private val bytes="ciphertext-only".toByteArray()
    private fun target()=CloudUploadObject("manifest",CloudSnapshotCodec.sha256(bytes),bytes.size.toLong(),"owner/path",
        "https://test.invalid/functions/v1/cloud/uploads/$id",reservationId=id,transport="epoch-proxy")
    private fun client(handler:(Request)->Pair<Int,JSONObject>)=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),
        deviceAccessToken={"app-access"},http=OkHttpClient.Builder().addInterceptor { chain ->
            val (status,body)=handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()).also{it.useEpoch(session,"epoch");it.currentDeviceId="phone"}
    @Test fun rawPutCarriesAccountDeviceAndEpochHeadersAndExactCiphertext()=runBlocking {
        var calls=0
        val c=client { request ->
            calls++;assertEquals("PUT",request.method);assertEquals("/functions/v1/cloud/uploads/$id",request.url.encodedPath)
            assertEquals("Bearer session",request.header("authorization"));assertEquals("Bearer app-access",request.header("x-watchrss-device-authorization"))
            assertEquals("epoch",request.header("x-watchrss-library-epoch"));assertEquals("phone",request.header("x-watchrss-cloud-device-id"))
            assertEquals("application/octet-stream",request.body!!.contentType().toString());assertNull(request.header("x-signature"))
            val body=okio.Buffer();request.body!!.writeTo(body);assertArrayEquals(bytes,body.readByteArray())
            200 to JSONObject()
        }
        c.uploadCiphertext(session,target(),bytes,"epoch");c.uploadCiphertext(session,target(),bytes,"epoch");assertEquals(2,calls)
    }
    @Test fun noLegacyTransportForeignBearerDestinationCorruptBytesOrStaleEpochCanUpload()=runBlocking {
        var calls=0;val c=client{calls++;200 to JSONObject()}
        suspend fun reject(target:CloudUploadObject, body:ByteArray=bytes, epoch:String="epoch") {
            try{c.uploadCiphertext(session,target,body,epoch);fail("unsafe upload")}
            catch(_:IllegalArgumentException){}catch(_:CloudTrustException){}
        }
        reject(target().copy(transport="storage-signed"));reject(target().copy(signedUrl="https://storage.invalid/steal"))
        reject(target(),"different".toByteArray());reject(target(),epoch="old")
        assertEquals(0,calls)
    }
    @Test fun resetDuringPutInvalidatesEpochAndNeverRetriesOldCiphertext()=runBlocking {
        var calls=0;var invalidated=0
        val c=client{calls++;409 to JSONObject().put("error","library_epoch_mismatch")};c.onEpochMismatch={invalidated++}
        try{c.uploadCiphertext(session,target(),bytes,"epoch");fail("reset ignored")}catch(e:CloudHttpException){assertEquals(409,e.status)}
        assertEquals(1,calls);assertEquals(1,invalidated);assertNull(c.knownEpoch(session))
    }
    @Test fun reserveAcceptsProxyShapeWithoutStorageTokensOrTusFields()=runBlocking {
        val snapshot=CloudSnapshotCodec().create(ByteArray(32),1,"phone",1,emptyList())
        val c=client { request ->
            assertEquals("/functions/v1/cloud/snapshots/reserve",request.url.encodedPath)
            200 to JSONObject().put("snapshotId",snapshot.manifest.snapshotId).put("reservedBytes",123).put("missingObjects",JSONArray().put(
                JSONObject().put("kind","manifest").put("sha256",CloudSnapshotCodec.sha256(snapshot.encryptedManifest)).put("sizeBytes",snapshot.encryptedManifest.size)
                    .put("objectPath","owner/path").put("uploadUrl","https://test.invalid/functions/v1/cloud/uploads/$id").put("reservationId",id)
                    .put("transport","epoch-proxy").put("uploadMethod","PUT")))
        }
        val value=c.reserveSnapshot(session,snapshot,30).missingObjects.single()
        assertEquals("epoch-proxy",value.transport);assertEquals("PUT",value.uploadMethod);assertEquals("",value.token);assertEquals("",value.tusEndpoint)
    }
    @Test fun r14BootstrapHidesReaderVirtualDevicesFromPhoneDeviceLists()=runBlocking {
        val c=client { _ ->200 to JSONObject().put("member",JSONObject()).put("keyEnvelopes",JSONArray()).put("libraryEpoch","epoch").put("keyVersionFloor",0)
            .put("keyCommitments",JSONArray()).put("devices",JSONArray().put(JSONObject().put("deviceId","phone").put("platform","phone"))
                .put(JSONObject().put("deviceId","reader-migration-fixture-v1").put("platform","phone"))) }
        assertEquals(listOf("phone"),c.bootstrap(session).devices.map{it.deviceId})
    }
}
