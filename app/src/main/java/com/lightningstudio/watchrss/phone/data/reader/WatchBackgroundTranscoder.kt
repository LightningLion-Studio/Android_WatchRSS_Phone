package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneWatchCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
            .forEach { asset -> prepare(asset, capabilities) }
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
        val edit = existing.optJSONObject("edit")
        val cropX = edit?.optDouble("cropX", 0.0)?.toFloat()?.coerceIn(-1f, 1f) ?: 0f
        val cropY = edit?.optDouble("cropY", 0.0)?.toFloat()?.coerceIn(-1f, 1f) ?: 0f
        val frameTimeMs = edit?.optLong("frameTimeMs", 0L)
            ?.coerceIn(0L, asset.durationMs.coerceAtLeast(0L)) ?: 0L
        val watch = existing.optJSONObject("watch")
        if (
            watch != null &&
            watch.optString("mime") == MimeTypes.VIDEO_H264 &&
            watch.optInt("width") == WATCH_VIDEO_SIZE &&
            watch.optInt("height") == WATCH_VIDEO_SIZE &&
            watch.optInt("bitrate") == WATCH_VIDEO_BITRATE &&
            watch.optInt("fps") == targetFps &&
            watch.optDouble("cropX", 0.0).toFloat() == cropX &&
            watch.optDouble("cropY", 0.0).toFloat() == cropY &&
            repository.resourceStore.variantFile(watch.optString("fileName"))?.isFile == true
        ) {
            return asset
        }

        val temp = repository.resourceStore.targetVariantFile(
            ".${asset.id}-${UUID.randomUUID()}.transcode"
        )
        temp.delete()
        val durationMs = asset.durationMs.coerceAtLeast(1L).coerceAtMost(60_000L)
        export(
            source = source,
            target = temp,
            sourceWidth = asset.width,
            sourceHeight = asset.height,
            cropX = cropX,
            cropY = cropY,
            fps = targetFps,
            durationMs = durationMs
        )
        val videoHash = repository.resourceStore.fileSha256(temp)
        val videoFile = repository.resourceStore.targetVariantFile(
            "${asset.id}-${UUID.randomUUID()}.mp4"
        )
        require(temp.renameTo(videoFile)) { "手表视频版本保存失败" }

        val posterTemp = repository.resourceStore.targetVariantFile(".${asset.id}.poster")
        posterTemp.delete()
        createPoster(source, posterTemp, frameTimeMs, cropX, cropY)
        val posterHash = repository.resourceStore.fileSha256(posterTemp)
        val posterFile = repository.resourceStore.targetVariantFile(
            "${asset.id}-${UUID.randomUUID()}-poster.jpg"
        )
        require(posterTemp.renameTo(posterFile)) { "视频封面保存失败" }

        val variants = JSONObject(asset.variantsJson.ifBlank { "{}" }).apply {
            put("watch", JSONObject().apply {
                put("fileName", videoFile.name)
                put("sha256", videoHash)
                put("byteCount", videoFile.length())
                put("mime", MimeTypes.VIDEO_H264)
                put("codec", "AVC")
                put("bitrate", WATCH_VIDEO_BITRATE)
                put("width", WATCH_VIDEO_SIZE)
                put("height", WATCH_VIDEO_SIZE)
                put("fps", targetFps)
                put("cropX", cropX.toDouble())
                put("cropY", cropY.toDouble())
                put("hardwareDecoder", profile.decoderName)
            })
            put("watchPoster", JSONObject().apply {
                put("fileName", posterFile.name)
                put("sha256", posterHash)
                put("byteCount", posterFile.length())
                put("mime", "image/jpeg")
                put("frameTimeMs", frameTimeMs)
            })
        }
        return asset.copy(variantsJson = variants.toString()).also {
            repository.updateBackgroundAsset(it)
        }
    }

    private suspend fun export(
        source: File,
        target: File,
        sourceWidth: Int,
        sourceHeight: Int,
        cropX: Float,
        cropY: Float,
        fps: Int,
        durationMs: Long
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
                        buildList {
                            cropEffect(sourceWidth, sourceHeight, cropX, cropY)?.let(::add)
                            add(
                                Presentation.createForWidthAndHeight(
                                    WATCH_VIDEO_SIZE,
                                    WATCH_VIDEO_SIZE,
                                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                                )
                            )
                        }
                    )
                )
                .build()
            lateinit var transformer: Transformer
            transformer = Transformer.Builder(appContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(appContext)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(WATCH_VIDEO_BITRATE)
                                .build()
                        )
                        .setEnableFallback(true)
                        .build()
                )
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

    private fun createPoster(
        source: File,
        output: File,
        timeMs: Long,
        cropX: Float,
        cropY: Float
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime(
                timeMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: error("无法生成视频封面")
            val square = cropSquareBitmap(bitmap, cropX, cropY)
            if (square !== bitmap) bitmap.recycle()
            val scaled = Bitmap.createScaledBitmap(
                square,
                WATCH_VIDEO_SIZE,
                WATCH_VIDEO_SIZE,
                true
            )
            if (scaled !== square) square.recycle()
            output.outputStream().buffered().use {
                require(scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "封面编码失败" }
            }
            scaled.recycle()
        } finally {
            retriever.release()
        }
    }

    private fun cropSquareBitmap(source: Bitmap, cropX: Float, cropY: Float): Bitmap {
        val side = minOf(source.width, source.height)
        val maxLeft = (source.width - side).coerceAtLeast(0)
        val maxTop = (source.height - side).coerceAtLeast(0)
        val left = (((cropX.coerceIn(-1f, 1f) + 1f) / 2f) * maxLeft).toInt()
            .coerceIn(0, maxLeft)
        val top = (((cropY.coerceIn(-1f, 1f) + 1f) / 2f) * maxTop).toInt()
            .coerceIn(0, maxTop)
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    private fun cropEffect(
        width: Int,
        height: Int,
        cropX: Float,
        cropY: Float
    ): Crop? {
        if (width <= 0 || height <= 0 || width == height) return null
        return if (width > height) {
            val halfWidth = height.toFloat() / width
            val center = cropX.coerceIn(-1f, 1f) * (1f - halfWidth)
            Crop(center - halfWidth, center + halfWidth, -1f, 1f)
        } else {
            val halfHeight = width.toFloat() / height
            val center = -cropY.coerceIn(-1f, 1f) * (1f - halfHeight)
            Crop(-1f, 1f, center - halfHeight, center + halfHeight)
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

    companion object {
        const val WATCH_VIDEO_SIZE = 466
        const val WATCH_VIDEO_BITRATE = 10_000_000
    }

    private fun selectProfile(capabilities: PhoneWatchCapabilities): SelectedProfile {
        val hardware = capabilities.videoDecoders.filter { it.hardwareAccelerated }
        val selected = hardware.firstOrNull {
            it.mime == MimeTypes.VIDEO_H264 &&
                it.maxWidth >= WATCH_VIDEO_SIZE &&
                it.maxHeight >= WATCH_VIDEO_SIZE
        } ?: error("手表没有满足 466×466 的 AVC 硬件解码器")
        return SelectedProfile(selected.name, selected.maxFrameRate)
    }
}

private data class SelectedProfile(
    val decoderName: String,
    val maxFrameRate: Double
)
