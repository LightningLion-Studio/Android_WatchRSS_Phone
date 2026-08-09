package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.NotePreviewBlock
import com.lightningstudio.watchrss.phone.parseNotePreviewBlocks
import org.json.JSONObject
import java.util.Base64

object NoteAssetSyncPayload {
    const val ACTION = "syncNoteAsset"
    const val VERSION = 1
    const val CHUNK_BYTES = 512 * 1024
    private val SafeStorageKey = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")

    internal fun referencedStorageKeys(markdown: String): Set<String> =
        parseNotePreviewBlocks(markdown)
            .asSequence()
            .filterIsInstance<NotePreviewBlock.Image>()
            .mapNotNull { image ->
                image.path.removePrefix("assets/")
                    .takeIf { image.path.startsWith("assets/") && SafeStorageKey.matches(it) }
            }
            .toCollection(linkedSetOf())

    internal fun isSafeStorageKey(storageKey: String): Boolean =
        SafeStorageKey.matches(storageKey)

    fun chunk(
        storageKey: String,
        sha256: String,
        chunkIndex: Int,
        chunkCount: Int,
        bytes: ByteArray
    ): JSONObject = JSONObject().apply {
        put("action", ACTION)
        put("version", VERSION)
        put("storageKey", storageKey)
        put("sha256", sha256)
        put("chunkIndex", chunkIndex)
        put("chunkCount", chunkCount)
        put("data", Base64.getEncoder().encodeToString(bytes))
    }
}
