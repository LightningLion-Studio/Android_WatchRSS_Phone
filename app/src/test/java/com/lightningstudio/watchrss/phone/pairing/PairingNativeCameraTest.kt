package com.lightningstudio.watchrss.phone.pairing

import android.util.Size
import android.graphics.BitmapFactory
import androidx.camera.core.AspectRatio
import androidx.camera.core.resolutionselector.ResolutionStrategy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import zxingcpp.BarcodeReader
import java.security.MessageDigest
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PairingNativeCameraTest {
    private val manifest=JSONObject(requireNotNull(javaClass.getResource("/pairing/watch-qr/manifest.json")).readText())
    private val expected=manifest.getString("payload")
    private val direct=Executor { it.run() }
    private fun pixels(file:String):Triple<Int,Int,ByteArray> {
        val image=requireNotNull(BitmapFactory.decodeStream(javaClass.getResourceAsStream("/pairing/watch-qr/$file")))
        val argb=IntArray(image.width*image.height);image.getPixels(argb,0,image.width,0,0,image.width,image.height)
        return Triple(image.width,image.height,ByteArray(argb.size) { i->(argb[i] and 255).toByte() })
    }
    @Test fun publishedAndroidReaderDecodesEveryOledCaptureAndComparesTheExactOldDecoder() {
        val reader=BarcodeReader(pairingBarcodeOptions());val fixtures=manifest.getJSONArray("fixtures")
        var nativePass=0;var legacyPass=0
        for(i in 0 until fixtures.length()) {
            val row=fixtures.getJSONObject(i);val name=row.getString("file")
            val bytes=requireNotNull(javaClass.getResourceAsStream("/pairing/watch-qr/$name")).readBytes()
            assertEquals(row.getString("sha256"),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
            val (w,h,luma)=pixels(name);assertEquals(466,w);assertEquals(466,h)
            val frame=PairingTestFrame(w,h,luma,padding=9)
            val found=reader.read(frame.image).singleOrNull()
            val legacy=legacyDecodePairingLuma(luma,w,h)
            if(found?.text==expected)nativePass++;if(legacy==expected)legacyPass++
            println("QR_COMPARE $name native=${found?.text==expected} legacy=${legacy==expected}")
            assertEquals("native $name",expected,found?.text);assertEquals("L",found?.ecLevel)
        }
        assertEquals(fixtures.length(),nativePass);assertTrue(nativePass>=legacyPass)
        println("QR_COMPARE_TOTAL native=$nativePass legacy=$legacyPass count=${fixtures.length()}")
    }
    @Test fun nativeReaderHandlesCameraRotationAndBlankFramesWithTheProductionQrOnlyOptions() {
        val options=pairingBarcodeOptions()
        assertEquals(setOf(BarcodeReader.Format.QR_CODE),options.formats)
        assertTrue(options.tryHarder&&options.tryRotate&&options.tryInvert);assertFalse(options.isPure)
        val reader=BarcodeReader(options);val(w,h,luma)=pixels("oled-466.png")
        for(rotation in listOf(0,90,180,270))assertEquals(expected,reader.read(PairingTestFrame(w,h,luma,rotation,7).image).single().text)
        val cameraFrame=ByteArray(1280*960){24}
        for(y in 0 until h)for(x in 0 until w)cameraFrame[(y+247)*1280+x+407]=luma[y*w+x]
        assertEquals(expected,reader.read(PairingTestFrame(1280,960,cameraFrame,padding=5).image).single().text)
        assertTrue(reader.read(PairingTestFrame(466,466,ByteArray(466*466){255.toByte()}).image).isEmpty())
    }
    @Test fun productionAnalyzerClosesEveryFrameIncludingIgnoredAndInvalidFrames() {
        val(w,h,luma)=pixels("oled-466.png");var delivered:String?=null
        val analyzer=PairingQrAnalyzer(direct,{delivered=it})
        val empty=PairingTestFrame(466,466,ByteArray(466*466){255.toByte()});analyzer.analyze(empty.image)
        assertEquals(1,empty.closes);assertNull(delivered)
        val first=PairingTestFrame(w,h,luma);analyzer.analyze(first.image)
        assertEquals(expected,delivered);assertEquals(1,first.closes)
        val ignored=PairingTestFrame(w,h,luma);analyzer.analyze(ignored.image);assertEquals(1,ignored.closes)
        val invalid=PairingTestFrame(w,h,luma)
        PairingQrAnalyzer(direct,{},decode={throw IllegalStateException("invalid frame")}).analyze(invalid.image)
        assertEquals(1,invalid.closes)
    }
    @Test fun frameClosesBeforeQueuedCallbackAndEachAnalyzerDeliversOnlyOnce() {
        val(w,h,luma)=pixels("scaled-350.png");val pending=mutableListOf<Runnable>();var calls=0
        val analyzer=PairingQrAnalyzer(Executor{pending+=it},{assertEquals(expected,it);calls++})
        val first=PairingTestFrame(w,h,luma);analyzer.analyze(first.image)
        val second=PairingTestFrame(w,h,luma);analyzer.analyze(second.image)
        assertEquals(1,first.closes);assertEquals(1,second.closes);assertEquals(0,calls);assertEquals(1,pending.size)
        pending.single().run();assertEquals(1,calls)
    }
    @Test fun cameraAnalysisRequestsHighFourByThreeResolutionWithSupportedDeviceFallback() {
        val analysis=PairingQrAnalyzer.imageAnalysis(direct,direct,{})
        val selector=requireNotNull(analysis.resolutionSelector)
        assertEquals(AspectRatio.RATIO_4_3,selector.aspectRatioStrategy.preferredAspectRatio)
        assertEquals(Size(1280,960),selector.resolutionStrategy!!.boundSize)
        assertEquals(ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,selector.resolutionStrategy!!.fallbackRule)
        analysis.clearAnalyzer()
    }
}
