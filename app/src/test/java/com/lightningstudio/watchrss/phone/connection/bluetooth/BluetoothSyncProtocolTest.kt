package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BluetoothSyncProtocolTest {
    @Test
    fun frameReadWrite_reportTransferredBytesInChunks() {
        val payload = JSONObject().apply {
            put("action", BluetoothSyncProtocol.ACTION_SYNC_LIBRARY)
            put("data", "x".repeat(80_000))
        }
        val output = ByteArrayOutputStream()
        val writeDeltas = mutableListOf<Long>()

        BluetoothSyncProtocol.writeFrame(output, payload) { bytes ->
            writeDeltas += bytes
        }

        val readDeltas = mutableListOf<Long>()
        val decoded = BluetoothSyncProtocol.readFrame(ByteArrayInputStream(output.toByteArray())) { bytes ->
            readDeltas += bytes
        }

        assertEquals(payload.toString(), decoded.toString())
        assertEquals(BluetoothSyncProtocol.wireSize(payload), writeDeltas.sum())
        assertEquals(BluetoothSyncProtocol.wireSize(payload), readDeltas.sum())
        assertEquals(BluetoothSyncProtocol.LENGTH_PREFIX_BYTES.toLong(), writeDeltas.first())
        assertEquals(BluetoothSyncProtocol.LENGTH_PREFIX_BYTES.toLong(), readDeltas.first())
        assertTrue(writeDeltas.size > 2)
        assertTrue(readDeltas.size > 2)
    }
}
