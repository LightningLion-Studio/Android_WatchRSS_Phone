package com.lightningstudio.watchrss.phone.pairing

// Exact pre-fix decoder from HEAD 7cef9c5, test-only comparison.
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

internal fun legacyDecodePairingLuma(luma:ByteArray,width:Int,height:Int):String? {
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
