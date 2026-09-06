package com.lightningstudio.watchrss.phone.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneWatchCapabilities
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneWatchVideoDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** A deterministic preparation error must not be retried as a Bluetooth disconnection. */
class WatchBackgroundPreparationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class WatchBackgroundTranscoder(context: Context, private val repository: ReaderPresetRepository) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    suspend fun prepareAll(capabilities: PhoneWatchCapabilities) {
        // A partially pulled library can contain remote metadata before its master arrives.
        // Leave those entries for the normal missing-resource pull; preview preparation is strict.
        repository.exportSnapshot().backgrounds.filter {
            !it.deleted && repository.resourceStore.backgroundFile(it.masterFileName) != null
        }.forEach { prepare(it, capabilities) }
    }

    suspend fun prepare(
        asset: ReaderBackgroundAssetEntity,
        capabilities: PhoneWatchCapabilities
    ): ReaderBackgroundAssetEntity = mutex.withLock {
        try {
            withContext(Dispatchers.IO) { prepareLocked(asset, capabilities) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: WatchBackgroundPreparationException) {
            throw failure
        } catch (failure: Exception) {
            Log.e("WatchBackgroundTranscoder", "Background preparation failed", failure)
            val reason = if (failure is ExportException) "手机不支持此视频的兼容 SDR 转换" else failure.message
            throw WatchBackgroundPreparationException("背景预处理失败：$reason", failure)
        }
    }

    private suspend fun prepareLocked(
        original: ReaderBackgroundAssetEntity,
        capabilities: PhoneWatchCapabilities
    ): ReaderBackgroundAssetEntity {
        require(capabilities.widthPx > 0 && capabilities.heightPx > 0) { "未获取到手表屏幕尺寸" }
        val asset = repository.exportSnapshot().backgrounds.firstOrNull { it.id == original.id } ?: original
        require(!asset.deleted) { "背景资源已删除" }
        val source = repository.resourceStore.backgroundFile(asset.masterFileName) ?: error("背景原文件不存在")
        val video = asset.kind == ReaderBackgroundType.VIDEO.name
        val metadata = if (video) videoMetadata(source) else null
        // ImageDecoder reports oriented dimensions and applies EXIF itself.
        var size: WatchBackgroundSize? = metadata?.let {
            watchBackgroundSize(it.width, it.height, capabilities.widthPx, capabilities.heightPx, it.rotation, true)
        }
        val profiles = if (video) capabilities.videoDecoders.filter {
            it.hardwareAccelerated && it.mime in listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264) &&
                it.maxWidth >= size!!.width && it.maxHeight >= size!!.height
        }.sortedBy { if (it.mime == MimeTypes.VIDEO_H265) 0 else 1 } else emptyList()
        require(!video || profiles.isNotEmpty()) { "手表没有满足全图目标尺寸的 H.265/H.264 硬件解码器" }
        val variants = runCatching { JSONObject(asset.variantsJson) }.getOrElse { JSONObject() }
        val watch = variants.optJSONObject("watch")
        if (watch != null && watch.optInt("processingVersion") == PROCESSING_VERSION &&
            watch.optString("sourceSha256") == asset.sha256 &&
            watch.optInt("displayWidth") == capabilities.widthPx &&
            watch.optInt("displayHeight") == capabilities.heightPx &&
            watch.optString("colorMode") == "sdr" &&
            repository.resourceStore.variantFile(watch.optString("fileName")) != null &&
            (!video || profiles.any { it.mime == watch.optString("mime") &&
                frameRate(capabilities, metadata!!, it) == watch.optInt("fps") } &&
                repository.resourceStore.variantFile(variants.optJSONObject("watchPoster")?.optString("fileName").orEmpty()) != null)
        ) {
            Log.d("WatchBackgroundTranscoder", "cache hit: ${watch.optInt("width")}x${watch.optInt("height")} SDR")
            return asset
        }

        val temp = repository.resourceStore.targetVariantFile(".${UUID.randomUUID()}.prepare.mp4")
        val posterTemp = repository.resourceStore.targetVariantFile(".${UUID.randomUUID()}.poster.png")
        try {
            var fps = 0
            var decoder = ""
            val mime: String
            if (video) {
                var used: PhoneWatchVideoDecoder? = null
                var lastFailure: Exception? = null
                for (profile in profiles.distinctBy { it.mime }) {
                    coroutineContext.ensureActive()
                    temp.delete()
                    try {
                        fps = frameRate(capabilities, metadata!!, profile)
                        val completed = withTimeoutOrNull(180_000L) {
                            export(source, temp, size!!, fps, metadata.durationMs, profile.mime)
                            true
                        }
                        if (completed == null) throw WatchBackgroundPreparationException("背景转码超时，请重试或选择较短的视频")
                        used = profile
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: WatchBackgroundPreparationException) { throw failure }
                    catch (failure: Exception) { lastFailure = failure }
                }
                val selected = used ?: throw checkNotNull(lastFailure)
                mime = selected.mime
                decoder = selected.name
                createPoster(temp, posterTemp)
            } else {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                    size = watchBackgroundSize(info.size.width, info.size.height, capabilities.widthPx, capabilities.heightPx)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setTargetSize(size!!.width, size!!.height)
                }
                try { writeSdrBitmap(bitmap, temp) } finally { bitmap.recycle() }
                mime = "image/png"
            }
            coroutineContext.ensureActive()
            val output = saveVariant(temp, if (video) "mp4" else "png")
            variants.put("watch", descriptor(output, mime).apply {
                put("processingVersion", PROCESSING_VERSION)
                put("sourceSha256", asset.sha256)
                put("sourceFileName", asset.masterFileName)
                put("displayWidth", capabilities.widthPx)
                put("displayHeight", capabilities.heightPx)
                put("width", size!!.width)
                put("height", size!!.height)
                put("colorMode", "sdr")
                put("colorSpace", if (video) "bt709" else "srgb")
                put("fps", fps)
                put("hardwareDecoder", decoder)
            })
            if (video) variants.put("watchPoster", descriptor(saveVariant(posterTemp, "png"), "image/png"))
            return repository.updateBackgroundVariants(asset.id, asset.sha256, variants.toString()).also {
                Log.d("WatchBackgroundTranscoder", "prepared: ${size!!.width}x${size!!.height} SDR $mime ${output.length()} bytes")
            }
        } finally {
            temp.delete()
            posterTemp.delete()
        }
    }

    private fun frameRate(caps: PhoneWatchCapabilities, source: VideoMetadata, profile: PhoneWatchVideoDecoder): Int =
        minOf(60, caps.refreshRateHz.roundToInt().coerceAtLeast(1), source.fps.coerceAtLeast(1),
            profile.maxFrameRate.toInt().coerceAtLeast(1))

    private suspend fun saveVariant(temp: File, extension: String): File {
        require(temp.length() > 0) { "预处理输出为空" }
        val hash = repository.resourceStore.fileSha256(temp)
        val target = repository.resourceStore.targetVariantFile("$hash.$extension")
        if (target.exists()) temp.delete() else check(temp.renameTo(target)) { "背景版本保存失败" }
        return target
    }

    private suspend fun descriptor(file: File, mime: String) = JSONObject().apply {
        put("fileName", file.name)
        put("sha256", repository.resourceStore.fileSha256(file))
        put("byteCount", file.length())
        put("mime", mime)
    }

    private suspend fun export(source: File, target: File, size: WatchBackgroundSize, fps: Int, durationMs: Long, mime: String) =
        withContext(Dispatchers.Main) {
            var activeTransformer: Transformer? = null
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                val media = MediaItem.Builder().setUri(source.toURI().toString())
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(durationMs.coerceIn(1L, 60_000L)).build()).build()
                val edited = EditedMediaItem.Builder(media).setRemoveAudio(true).setFrameRate(fps)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                        size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT),
                        FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat())))).build()
                val composition = Composition.Builder(EditedMediaItemSequence(edited))
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
                val transformer = Transformer.Builder(appContext).setVideoMimeType(mime)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(appContext).setEnableFallback(false).build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (!continuation.isActive) return
                            val result = runCatching {
                                val actual = videoMetadata(target)
                                val rotated = Math.floorMod(actual.rotation, 180) == 90
                                val width = if (rotated) actual.height else actual.width
                                val height = if (rotated) actual.width else actual.height
                                require(width == size.width && height == size.height) { "编码器改变了目标尺寸" }
                                require(exportResult.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR && !actual.hdr) { "视频未转换为 SDR" }
                                require(exportResult.videoMimeType == mime) { "编码器改变了目标编码格式" }
                                require(target.length() > 0) { "视频输出为空" }
                            }
                            result.fold({ continuation.resume(Unit) }, { continuation.resumeWithException(it) })
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                activeTransformer = transformer
                transformer.start(composition, target.absolutePath)
                }
            } finally {
                // Stay on the application's main looper and finish release before the
                // caller deletes/reuses the output or starts a fallback export.
                activeTransformer?.cancel()
            }
        }

    private fun createPoster(source: File, output: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("无法生成视频封面")
            try { writeSdrBitmap(bitmap, output) } finally { bitmap.recycle() }
        } finally { retriever.release() }
    }

    private fun writeSdrBitmap(bitmap: Bitmap, output: File) {
        // Ultra HDR's base rendition is SDR; do not carry its gain map into the PNG.
        if (Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
        val sdr = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888,
            bitmap.hasAlpha(), ColorSpace.get(ColorSpace.Named.SRGB))
        try {
            Canvas(sdr).drawBitmap(bitmap, 0f, 0f, null)
            output.outputStream().buffered().use { require(sdr.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { sdr.recycle() }
    }

    private fun videoMetadata(file: File): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun value(key: Int) = retriever.extractMetadata(key)
            val format = sourceVideoFormat(file)
            val sourceFps = if (format?.containsKey(MediaFormat.KEY_FRAME_RATE) == true)
                format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble()?.roundToInt() else null
            val transfer = if (format?.containsKey(MediaFormat.KEY_COLOR_TRANSFER) == true)
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            return VideoMetadata(
                value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                value(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("无法读取视频时长"),
                sourceFps ?: value(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.roundToInt() ?: 60,
                transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG)
        } finally { retriever.release() }
    }

    private fun sourceVideoFormat(file: File): MediaFormat? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return format
            }
            return null
        } finally { extractor.release() }
    }

    private data class VideoMetadata(val width: Int, val height: Int, val rotation: Int, val durationMs: Long, val fps: Int, val hdr: Boolean)
    companion object { internal const val PROCESSING_VERSION = 2 }
}
