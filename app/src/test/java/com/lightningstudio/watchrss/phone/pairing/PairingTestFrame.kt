package com.lightningstudio.watchrss.phone.pairing

import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Direct padded Y plane, a real Android Rect and CameraX interfaces; JNI sees the actual buffer. */
internal class PairingTestFrame(width:Int,height:Int,pixels:ByteArray,rotation:Int=0,padding:Int=0) {
    var closes=0
    val crop=Rect(padding,padding,padding+width,padding+height)
    val stride=width+padding*2+13
    val buffer:ByteBuffer=ByteBuffer.allocateDirect(stride*(height+padding*2)).also { b ->
        for(y in 0 until height)for(x in 0 until width)b.put((y+padding)*stride+x+padding,pixels[y*width+x])
    }
    private val plane=proxy(ImageProxy.PlaneProxy::class.java) { method -> when(method) {
        "getBuffer"->buffer.duplicate();"getRowStride"->stride;"getPixelStride"->1;else->null
    } }
    private val info=proxy(ImageInfo::class.java) { method ->when(method) {"getRotationDegrees"->rotation;"getTimestamp"->0L;else->null} }
    val image:ImageProxy=proxy(ImageProxy::class.java) { method ->when(method) {
        "getFormat"->ImageFormat.YUV_420_888;"getWidth"->width+padding*2;"getHeight"->height+padding*2
        "getPlanes"->arrayOf(plane);"getCropRect"->crop;"getImageInfo"->info;"close"->{closes++;null};else->null
    } }
    private fun <T:Any> proxy(type:Class<T>,read:(String)->Any?):T=requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { p,m,args ->
        when(m.name) {"toString"->"SyntheticPairingFrame";"hashCode"->System.identityHashCode(p);"equals"->(p===args?.firstOrNull());else->read(m.name)}
    }))
}
