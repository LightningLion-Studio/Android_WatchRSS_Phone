package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneWatchCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class WatchBackgroundTranscoder(
    context: Context,
    private val repository: ReaderPresetRepository
) {
    private val appContext = context.applicationContext

    suspend fun prepareAll(capabilities: PhoneWatchCapabilities) {
        if (capabilities.widthPx <= 0 || capabilities.heightPx <= 0) return
        repository.backgrounds.value
            .filter { !it.deleted && it.kind == ReaderBackgroundType.VIDEO.name }
            .forEach { asset ->
                runCatching { prepare(asset, capabilities) }
            }
    }

    suspend fun prepare(
        asset: ReaderBackgroundAssetEntity,
        capabilities: PhoneWatchCapabilities
    ): ReaderBackgroundAssetEntity {
        val source = repository.resourceStore.backgroundFile(asset.masterFileName)
            ?: error("背景原视频不存在")
        val profile = selectProfile(capabilities)
        val sourceFps = sourceFrameRate(source)
        val targetFps = minOf(
            60,
            capabilities.refreshRateHz.roundToInt().coerceAtLeast(1),
            sourceFps.coerceAtLeast(1),
            profile.maxFrameRate.roundToInt().coerceAtLeast(1)
        )
        val existing = runCatching { JSONObject(asset.variantsJson) }.getOrElse { JSONObject() }
        val watch = existing.optJSONObject("watch")
        if (
            watch != null &&
            watch.optInt("width") == capabilities.widthPx &&
            watch.optInt("height") == capabilities.heightPx &&
            watch.optInt("fps") == targetFps &&
            repository.resourceStore.variantFile(watch.optString("fileName"))?.isFile == true
        ) {
            return asset
        }

        val temp = repository.resourceStore.targetVariantFile(
            ".${asset.sha256}-${capabilities.widthPx}x${capabilities.heightPx}.transcode"
        )
        temp.delete()
        val durationMs = asset.durationMs.coerceAtLeast(1L).coerceAtMost(60_000L)
        val preferredMime = profile.mime
        val usedMime = runCatching {
            export(
                source = source,
                target = temp,
                width = capabilities.widthPx,
                height = capabilities.heightPx,
                fps = targetFps,
                durationMs = durationMs,
                mime = preferredMime
            )
            preferredMime
        }.getOrElse {
            temp.delete()
            require(preferredMime != MimeTypes.VIDEO_H264) { throw it }
            export(
                source = source,
                target = temp,
                width = capabilities.widthPx,
                height = capabilities.heightPx,
                fps = targetFps,
                durationMs = durationMs,
                mime = MimeTypes.VIDEO_H264
            )
            MimeTypes.VIDEO_H264
        }
        val videoHash = repository.resourceStore.fileSha256(temp)
        val videoFile = repository.resourceStore.targetVariantFile("$videoHash.mp4")
        if (!videoFile.exists()) require(temp.renameTo(videoFile)) { "手表视频版本保存失败" }
        else temp.delete()

        val posterTemp = repository.resourceStore.targetVariantFile(".${asset.sha256}.poster")
        createPoster(source, posterTemp)
        val posterHash = repository.resourceStore.fileSha256(posterTemp)
        val posterFile = repository.resourceStore.targetVariantFile("$posterHash.jpg")
        if (!posterFile.exists()) require(posterTemp.renameTo(posterFile)) { "视频封面保存失败" }
        else posterTemp.delete()

        val variants = JSONObject(asset.variantsJson.ifBlank { "{}" }).apply {
            put("watch", JSONObject().apply {
                put("fileName", videoFile.name)
                put("sha256", videoHash)
                put("byteCount", videoFile.length())
                put("mime", usedMime)
                put("width", capabilities.widthPx)
                put("height", capabilities.heightPx)
                put("fps", targetFps)
                put("hardwareDecoder", profile.decoderName)
            })
            put("watchPoster", JSONObject().apply {
                put("fileName", posterFile.name)
                put("sha256", posterHash)
                put("byteCount", posterFile.length())
                put("mime", "image/jpeg")
            })
        }
        return asset.copy(variantsJson = variants.toString()).also {
            repository.updateBackgroundAsset(it)
        }
    }

    private suspend fun export(
        source: File,
        target: File,
        width: Int,
        height: Int,
        fps: Int,
        durationMs: Long,
        mime: String
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val mediaItem = MediaItem.Builder()
                .setUri(source.toURI().toString())
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0)
                        .setEndPositionMs(durationMs.coerceAtMost(60_000L))
                        .build()
                )
                .build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true)
                .setFrameRate(fps)
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(
                            Presentation.createForWidthAndHeight(
                                width,
                                height,
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                            )
                        )
                    )
                )
                .build()
            lateinit var transformer: Transformer
            transformer = Transformer.Builder(appContext)
                .setVideoMimeType(mime)
                .setRemoveAudio(true)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(exportException)
                        }
                    }
                })
                .build()
            continuation.invokeOnCancellation { transformer.cancel() }
            transformer.start(edited, target.absolutePath)
        }
    }

    private fun createPoster(source: File, output: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("无法生成视频封面")
            output.outputStream().buffered().use {
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "封面编码失败" }
            }
            bitmap.recycle()
        } finally {
            retriever.release()
        }
    }

    private fun sourceFrameRate(source: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.roundToInt()
                ?: 60
        } finally {
            retriever.release()
        }
    }

    private fun selectProfile(capabilities: PhoneWatchCapabilities): SelectedProfile {
        val hardware = capabilities.videoDecoders.filter { it.hardwareAccelerated }
        val h265 = hardware.firstOrNull {
            it.mime == MimeTypes.VIDEO_H265 &&
                it.maxWidth >= capabilities.widthPx &&
                it.maxHeight >= capabilities.heightPx
        }
        val selected = h265 ?: hardware.firstOrNull {
            it.mime == MimeTypes.VIDEO_H264 &&
                it.maxWidth >= capabilities.widthPx &&
                it.maxHeight >= capabilities.heightPx
        } ?: error("手表没有满足目标分辨率的 H.265/H.264 硬件解码器")
        return SelectedProfile(selected.mime, selected.name, selected.maxFrameRate)
    }
}

private data class SelectedProfile(
    val mime: String,
    val decoderName: String,
    val maxFrameRate: Double
)
