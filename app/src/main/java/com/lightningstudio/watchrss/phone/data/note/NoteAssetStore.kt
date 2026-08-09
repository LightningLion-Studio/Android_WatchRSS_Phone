package com.lightningstudio.watchrss.phone.data.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

data class StoredNoteAsset(
    val entity: NoteAssetEntity,
    val additionalAssets: List<NoteAssetEntity>,
    val pixelWidth: Int,
    val pixelHeight: Int
)

/** Stores a sync-safe app-private image; normal imports are bounded to 680 pixels. */
class NoteAssetStore(private val context: Context) {
    fun discardImportedAsset(storageKey: String) {
        require(storageKey == File(storageKey).name) { "图片文件名无效" }
        File(context.filesDir, "notes/assets/$storageKey").delete()
    }

    suspend fun importImage(noteId: String, uri: Uri, keepOriginal: Boolean): StoredNoteAsset {
        if (keepOriginal) {
            val original = copyOriginal(noteId, uri)
            val (pixelWidth, pixelHeight) = imagePixelDimensions(
                File(context.filesDir, "notes/assets/${original.storageKey}")
            ) ?: (1 to 1)
            return StoredNoteAsset(
                original,
                emptyList(),
                pixelWidth,
                pixelHeight
            )
        }
        val assetId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "notes/assets").also { it.mkdirs() }
        val target = File(directory, "$assetId.jpg")
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取图片" }
            requireNotNull(BitmapFactory.decodeStream(input)) { "图片格式不受支持" }
        }
        val scaled = scaleTo680Pixels(bitmap)
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
        return StoredNoteAsset(
            entity,
            emptyList(),
            scaled.width,
            scaled.height
        )
    }

    private fun scaleTo680Pixels(source: Bitmap): Bitmap {
        val (width, height) = scaledImageDimensions(source.width, source.height)
        if (width == source.width && height == source.height) return source
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun copyOriginal(noteId: String, uri: Uri): NoteAssetEntity {
        val assetId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "notes/assets").also { it.mkdirs() }
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val extension = when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            else -> "original"
        }
        val target = File(directory, "$assetId.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取原图" }
            FileOutputStream(target).use(input::copyTo)
        }
        val displayName = uri.lastPathSegment?.substringAfterLast('/')?.take(120).orEmpty().ifBlank { "图片" }
        return NoteAssetEntity(
            assetId = assetId, noteId = noteId, sha256 = target.inputStream().use(::sha256),
            displayName = displayName, mimeType = mimeType,
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

private fun imagePixelDimensions(file: File): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth to options.outHeight
    } else {
        null
    }
}

internal fun scaledImageDimensions(width: Int, height: Int, maxEdge: Int = 680): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return width to height
    val ratio = maxEdge.toFloat() / longest
    return maxOf(1, (width * ratio).roundToInt()) to maxOf(1, (height * ratio).roundToInt())
}
