package com.lightningstudio.watchrss.phone.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** No preview/layout or permission request. Lead binds Preview + this ImageAnalysis to the page lifecycle. */
class PairingQrAnalyzer(private val resultExecutor:Executor,private val onDecoded:(String)->Unit):ImageAnalysis.Analyzer {
    private val delivered=AtomicBoolean(false)
    fun reset() {delivered.set(false)}
    override fun analyze(image:ImageProxy) {
        try {
            if(delivered.get())return
            val crop=image.cropRect;val plane=image.planes.firstOrNull()?:return
            val luma=readPairingLuma(plane.buffer,plane.rowStride,plane.pixelStride,crop.left,crop.top,crop.width(),crop.height())
            val decoded=decodePairingLuma(luma,crop.width(),crop.height())?:return
            if(delivered.compareAndSet(false,true)) resultExecutor.execute {onDecoded(decoded)}
        } catch(_:IllegalArgumentException) { /* A cropped/incomplete frame is retried on the next image. */ }
        finally {image.close()}
    }
    companion object {
        fun imageAnalysis(analysisExecutor:Executor,resultExecutor:Executor,onDecoded:(String)->Unit):ImageAnalysis =
            ImageAnalysis.Builder().setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                it.setAnalyzer(analysisExecutor,PairingQrAnalyzer(resultExecutor,onDecoded))
            }
    }
}
internal fun readPairingLuma(buffer:ByteBuffer,rowStride:Int,pixelStride:Int,left:Int,top:Int,width:Int,height:Int):ByteArray {
    require(rowStride>0&&pixelStride>0&&left>=0&&top>=0&&width>0&&height>0&&width.toLong()*height<=16_000_000)
    val source=buffer.duplicate();val base=source.position()
    require(base.toLong()+(top+height-1L)*rowStride+(left+width-1L)*pixelStride<source.limit())
    return ByteArray(width*height).also {out -> for(y in 0 until height)for(x in 0 until width)
        out[y*width+x]=source.get(base+(top+y)*rowStride+(left+x)*pixelStride)
    }
}
internal fun decodePairingLuma(luma:ByteArray,width:Int,height:Int):String? {
    require(luma.size==width*height)
    val reader=MultiFormatReader().apply {setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true,DecodeHintType.CHARACTER_SET to "UTF-8"))}
    var pixels=luma;var frameWidth=width;var frameHeight=height
    repeat(4) { turn ->
        val source=PlanarYUVLuminanceSource(pixels,frameWidth,frameHeight,0,0,frameWidth,frameHeight,false)
        try {return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text}
        catch(_:ReaderException) {} finally {reader.reset()}
        // PlanarYUVLuminanceSource cannot rotate. Rotate the compact crop, including non-square frames.
        if(turn<3) {
            val previous=pixels;val oldWidth=frameWidth;val oldHeight=frameHeight
            pixels=ByteArray(previous.size) { index ->
                val x=index%oldHeight;val y=index/oldHeight
                previous[x*oldWidth+(oldWidth-1-y)]
            }
            frameWidth=oldHeight;frameHeight=oldWidth
        }
    }
    return null
}
