package com.lightningstudio.watchrss.phone.cloud

import java.io.IOException

const val CLOUD_LIBRARY_DELETE_PHRASE = "删除云端库"

fun isCloudLibraryDeleteConfirmed(input: String): Boolean =
    input == CLOUD_LIBRARY_DELETE_PHRASE

fun cloudLibraryResetErrorMessage(error: Throwable): String {
    val detail = error.message.orEmpty()
    return when {
        detail.startsWith("HTTP 404") -> "云端服务尚未启用资料库重置，请稍后重试"
        detail.startsWith("HTTP 401") -> "登录已过期，请重新登录"
        detail.startsWith("HTTP 403") -> "当前账号无权重置云端库"
        error is IOException -> "网络连接失败，请检查网络后重试"
        else -> "云端库删除失败，请稍后重试"
    }
}

data class CloudLibraryResetResult(
    val snapshotsDeleted: Long,
    val chunksDeleted: Long,
    val releasedBytes: Long,
    val storageObjectsQueued: Long
)
