package com.lightningstudio.watchrss.phone.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Small immutable blocks use the same signed PUT route as the mini-program. */
internal class CloudSignedObjectUploader(private val http: OkHttpClient) {
    fun upload(target: CloudUploadObject, bytes: ByteArray) {
        require(bytes.size.toLong() == target.sizeBytes) { "待上传云对象长度不匹配" }
        require(CloudSnapshotCodec.sha256(bytes) == target.sha256) { "待上传云对象哈希不匹配" }
        val request = Request.Builder().url(target.signedUrl)
            .header("cache-control", "3600")
            .put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            // A completed immutable object from a previous attempt is verified again by complete.
            val duplicate = response.code == 409 || (response.code == 400 && runCatching {
                val value = org.json.JSONObject(response.body?.string().orEmpty())
                value.optString("error") == "Duplicate" ||
                    value.optString("code") in setOf("KeyAlreadyExists", "ResourceAlreadyExists")
            }.getOrDefault(false))
            if (!response.isSuccessful && !duplicate) {
                throw CloudObjectUploadException(response.code)
            }
        }
    }
}

internal class CloudObjectUploadException(val status: Int) : IOException("云端上传失败（HTTP $status）")
