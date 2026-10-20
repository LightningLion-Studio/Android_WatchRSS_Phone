package com.lightningstudio.watchrss.phone.cloud

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudSignedObjectUploaderTest {
    private val bytes = "encrypted payload".toByteArray()
    private fun target() = CloudUploadObject("chunk", CloudSnapshotCodec.sha256(bytes), bytes.size.toLong(),
        "owner/chunk", "https://storage.invalid/object/upload/sign/chunk?token=secret", "secret", "https://storage.invalid/upload/resumable", "private")

    @Test fun smallBlockUsesOneRawSignedPutInsteadOfTusHandshake() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("PUT", request.method)
            assertEquals("/object/upload/sign/chunk", request.url.encodedPath)
            assertEquals("secret", request.url.queryParameter("token"))
            assertArrayEquals(bytes, Buffer().also { requireNotNull(request.body).writeTo(it) }.readByteArray())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        CloudSignedObjectUploader(http).upload(target(), bytes)
        assertEquals(1, calls)
    }

    @Test fun failuresRemainFailuresAndDoNotExposeStorageTokens() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("secret signed URL token".toResponseBody()).build()
        }.build()
        val error = assertThrows(IOException::class.java) { CloudSignedObjectUploader(http).upload(target(), bytes) }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertThrows(IllegalArgumentException::class.java) { CloudSignedObjectUploader(http).upload(target(), byteArrayOf(1)) }
    }
    @Test fun providerDuplicate400CanProceedToFinalHashVerification() {
        fun uploader(body: String) = CloudSignedObjectUploader(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body(body.toResponseBody()).build()
        }.build())
        uploader("{\"error\":\"Duplicate\",\"statusCode\":\"409\"}").upload(target(), bytes)
        assertThrows(CloudObjectUploadException::class.java) {
            uploader("{\"error\":\"InvalidJWT\"}").upload(target(), bytes)
        }
        assertThrows(CloudObjectUploadException::class.java) {
            uploader("The resource already exists").upload(target(), bytes)
        }
    }

}
