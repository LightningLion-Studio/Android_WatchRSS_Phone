package com.lightningstudio.watchrss.phone.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PairingCameraTest {
    @Test fun cameraLumaHandlesPlanePositionCropRowPaddingAndPixelStride() {
        val bytes=ByteArray(70){99};val buffer=ByteBuffer.wrap(bytes);buffer.position(3)
        for(y in 0..2)for(x in 0..3)bytes[3+y*15+x*2]=(10*y+x).toByte()
        assertArrayEquals(byteArrayOf(11,12,21,22),readPairingLuma(buffer,15,2,1,1,2,2))
        assertEquals(3,buffer.position())
        assertThrows(IllegalArgumentException::class.java){readPairingLuma(buffer,100,2,0,0,4,5)}
    }
    @Test fun zxingDecodesSameInternetQrFromAPreviewLumaFrameAndItsRotations() {
        val text=PairingTestData.raw;val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,640,640)
        var luma=ByteArray(640*640){i->if(matrix[i%640,i/640])0 else 255.toByte()}
        repeat(4) {
            assertEquals(text,decodePairingLuma(luma,640,640))
            val old=luma;luma=ByteArray(old.size){i->val x=i%640;val y=i/640;old[(639-x)*640+y]}
        }
        assertNull(decodePairingLuma(ByteArray(64*64){255.toByte()},64,64))
    }
}
