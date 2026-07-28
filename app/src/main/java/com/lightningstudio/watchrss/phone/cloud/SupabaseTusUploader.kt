package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

class SupabaseTusUploader(
    context: Context,
    private val http: OkHttpClient = OkHttpClient()
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_tus_uploads",
        Context.MODE_PRIVATE
    )

    suspend fun upload(target: CloudUploadObject, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            require(bytes.size.toLong() == target.sizeBytes) { "待上传云对象长度不匹配" }
            require(CloudSnapshotCodec.sha256(bytes) == target.sha256) { "待上传云对象哈希不匹配" }
            var uploadUrl = preferences.getString(target.sha256, null)
            val existingOffset = uploadUrl?.let { queryOffset(it, target.token) }
            var offset: Long
            if (existingOffset == null) {
                uploadUrl = createUpload(target)
                if (uploadUrl == null) {
                    preferences.edit().remove(target.sha256).apply()
                    return@withContext
                }
                offset = 0L
                preferences.edit().putString(target.sha256, uploadUrl).commit()
            } else {
                offset = existingOffset
            }
            require(offset in 0..bytes.size.toLong()) { "TUS服务端返回了无效偏移量" }
            while (offset < bytes.size) {
                val end = minOf(bytes.size.toLong(), offset + TUS_CHUNK_BYTES).toInt()
                offset = patch(
                    uploadUrl = requireNotNull(uploadUrl),
                    token = target.token,
                    offset = offset,
                    bytes = bytes.copyOfRange(offset.toInt(), end),
                    totalBytes = bytes.size.toLong()
                )
            }
            require(offset == bytes.size.toLong()) { "TUS上传未完成" }
            preferences.edit().remove(target.sha256).apply()
        }

    private fun createUpload(target: CloudUploadObject): String? {
        val metadata = listOf(
            "bucketName" to target.bucketName,
            "objectName" to target.objectPath,
            "contentType" to "application/octet-stream",
            "cacheControl" to "3600"
        ).joinToString(",") { (name, value) ->
            "$name ${Base64.getEncoder().encodeToString(value.toByteArray())}"
        }
        val request = Request.Builder()
            .url(target.tusEndpoint)
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Length", target.sizeBytes.toString())
            .header("Upload-Metadata", metadata)
            .header("x-signature", target.token)
            .header("x-upsert", "false")
            .post(ByteArray(0).toRequestBody(OCTET_STREAM))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 409) return null
            if (response.code !in 200..299) throw response.uploadError()
            val location = response.header("Location")
                ?: throw IOException("TUS服务端未返回上传地址")
            return response.request.url.resolve(location)?.toString()
                ?: throw IOException("TUS上传地址无效")
        }
    }

    private fun queryOffset(uploadUrl: String, token: String): Long? {
        val request = Request.Builder()
            .url(uploadUrl)
            .header("Tus-Resumable", TUS_VERSION)
            .header("x-signature", token)
            .head()
            .build()
        return http.newCall(request).execute().use { response ->
            when {
                response.code == 404 || response.code == 410 -> null
                !response.isSuccessful -> throw response.uploadError()
                else -> response.header("Upload-Offset")?.toLongOrNull()
                    ?: throw IOException("TUS服务端未返回上传偏移量")
            }
        }
    }

    private fun patch(
        uploadUrl: String,
        token: String,
        offset: Long,
        bytes: ByteArray,
        totalBytes: Long
    ): Long {
        val request = Request.Builder()
            .url(uploadUrl)
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Offset", offset.toString())
            .header("x-signature", token)
            .patch(bytes.toRequestBody(TUS_CONTENT))
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 409) return totalBytes
            if (!response.isSuccessful) throw response.uploadError()
            response.header("Upload-Offset")?.toLongOrNull()
                ?: throw IOException("TUS服务端未返回上传偏移量")
        }
    }

    private fun okhttp3.Response.uploadError(): IOException {
        val detail = body?.string().orEmpty()
        return IOException("TUS HTTP $code: ${detail.ifBlank { message }}")
    }

    private companion object {
        private const val TUS_VERSION = "1.0.0"
        private const val TUS_CHUNK_BYTES = 6L * 1024L * 1024L
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val TUS_CONTENT = "application/offset+octet-stream".toMediaType()
    }
}
