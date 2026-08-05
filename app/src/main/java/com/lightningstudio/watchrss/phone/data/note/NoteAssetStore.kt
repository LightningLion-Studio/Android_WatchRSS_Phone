package com.lightningstudio.watchrss.phone.data.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class StoredNoteAsset(val entity: NoteAssetEntity, val markdownPath: String, val additionalAssets: List<NoteAssetEntity>)

/** Stores a sync-safe app-private image; normal imports are bounded to 720p. */
class NoteAssetStore(private val context: Context) {
    suspend fun importImage(noteId: String, uri: Uri, keepOriginal: Boolean): StoredNoteAsset {
        val assetId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "notes/assets").also { it.mkdirs() }
        val target = File(directory, "$assetId.jpg")
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取图片" }
            requireNotNull(BitmapFactory.decodeStream(input)) { "图片格式不受支持" }
        }
        val scaled = scaleTo720p(bitmap)
        FileOutputStream(target).use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "图片压缩失败" }
        }
        if (scaled !== bitmap) bitmap.recycle()
        val sourceName = uri.lastPathSegment?.substringAfterLast('/')?.take(120).orEmpty().ifBlank { "图片" }
        val entity = NoteAssetEntity(
            assetId = assetId,
            noteId = noteId,
            sha256 = target.inputStream().use(::sha256),
            displayName = sourceName,
            mimeType = "image/jpeg",
            byteCount = target.length(),
            storageKey = target.name,
            isOriginal = false,
            createdAt = System.currentTimeMillis()
        )
        val original = if (keepOriginal) copyOriginal(noteId, uri, directory, sourceName) else null
        return StoredNoteAsset(entity, "assets/${target.name}", listOfNotNull(original))
    }

    private fun scaleTo720p(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= 1280) return source
        val ratio = 1280f / longest
        return Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true)
    }

    private fun copyOriginal(noteId: String, uri: Uri, directory: File, displayName: String): NoteAssetEntity {
        val assetId = UUID.randomUUID().toString()
        val target = File(directory, "$assetId.original")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取原图" }
            FileOutputStream(target).use(input::copyTo)
        }
        return NoteAssetEntity(
            assetId = assetId, noteId = noteId, sha256 = target.inputStream().use(::sha256),
            displayName = displayName, mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
            byteCount = target.length(), storageKey = target.name, isOriginal = true, createdAt = System.currentTimeMillis()
        )
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
