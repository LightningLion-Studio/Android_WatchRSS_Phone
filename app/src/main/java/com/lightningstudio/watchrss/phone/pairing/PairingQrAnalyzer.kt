package com.lightningstudio.watchrss.phone.pairing

import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import zxingcpp.BarcodeReader
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal fun pairingBarcodeOptions() = BarcodeReader.Options(
    formats = setOf(BarcodeReader.Format.QR_CODE), tryHarder = true, tryRotate = true,
    tryInvert = true, tryDownscale = true, tryDenoise = true, maxNumberOfSymbols = 1,
    textMode = BarcodeReader.TextMode.PLAIN
)

/** No layout or permissions. BarcodeReader reads the CameraX crop/rotation/Y-plane directly. */
class PairingQrAnalyzer internal constructor(
    private val resultExecutor: Executor, private val onDecoded: (String) -> Unit,
    private val decode: (ImageProxy) -> String?
) : ImageAnalysis.Analyzer {
    constructor(resultExecutor: Executor, onDecoded: (String) -> Unit) : this(resultExecutor, onDecoded, nativeDecoder())
    private val delivered = AtomicBoolean(false)
    fun reset() { delivered.set(false) }
    override fun analyze(image: ImageProxy) {
        try {
            if (delivered.get()) return
            val decoded = decode(image) ?: return
            if (delivered.compareAndSet(false, true)) resultExecutor.execute { onDecoded(decoded) }
        } catch (_: RuntimeException) { /* A closed/incomplete frame is retried on the next image. */ }
        finally { image.close() }
    }
    companion object {
        private fun nativeDecoder(): (ImageProxy) -> String? {
            val reader by lazy { BarcodeReader(pairingBarcodeOptions()) }
            return { image -> reader.read(image).firstOrNull { it.error == null }?.text?.takeIf { it.isNotBlank() } }
        }
        fun imageAnalysis(analysisExecutor: Executor, resultExecutor: Executor, onDecoded: (String) -> Unit): ImageAnalysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // CameraX manages continuous-picture AF and restores it after tap focus.
                // Do not pin Camera2 AF_MODE: that would override the UI's FocusMeteringAction.
                .build().also { it.setAnalyzer(analysisExecutor, PairingQrAnalyzer(resultExecutor, onDecoded)) }
    }
}
