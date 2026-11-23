package com.lightningstudio.watchrss.phone.connection.watchs

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchSBleProtocolTest {
    private val frames = WatchSBleFrames

    @Test fun frameRoundTripRejectsCorruptionAndOversizedMtu() {
        val value = WatchSBleFrames.Frame(frames.FINAL, 0xfedcba98L, 9, byteArrayOf(0, 1, 2, 3, 4, 5))
        val wire = frames.encode(value)
        assertEquals("5702fedcba9800000009", wire.take(10).joinToString("") { "%02x".format(it) })
        assertEquals(20, wire.size)
        val decoded = frames.decode(wire, 23)
        assertEquals(value.type, decoded.type)
        assertEquals(value.id, decoded.id)
        assertEquals(value.offset, decoded.offset)
        assertArrayEquals(value.payload, decoded.payload)
        wire[19] = 6
        assertThrows(IllegalArgumentException::class.java) { frames.decode(wire, 23) }
        val larger = frames.encode(value.copy(payload = ByteArray(7)))
        assertThrows(IllegalArgumentException::class.java) { frames.decode(larger, 23) }
    }

    @Test fun fragmentsHonorMtuAndPreservePayload() {
        val body = ByteArray(997) { it.toByte() }
        for (mtu in listOf(23, 64, 247)) {
            val receiver = WatchSBleFrames.Receiver()
            var message: ByteArray? = null
            for (frame in frames.fragments(1, body, mtu)) {
                val wire = frames.encode(frame)
                assertTrue(wire.size <= mtu - 3)
                val accepted = receiver.accept(frames.decode(wire, mtu))!!
                assertEquals(frame.end, accepted.ack.offset)
                message = accepted.message
            }
            assertArrayEquals(body, message)
        }
    }

    @Test fun lostAckContinuesFromPrefixAndDuplicateFinalDoesNotReapply() {
        val receiver = WatchSBleFrames.Receiver()
        val body = ByteArray(7) { it.toByte() }
        val (first, last) = frames.fragments(1, body, 23).toList()
        assertNull(receiver.accept(last))
        assertEquals(6, receiver.accept(first)!!.ack.offset)
        assertNull(receiver.accept(first)!!.message)
        assertNull(receiver.accept(first.copy(payload = ByteArray(6) { 9 })))
        assertArrayEquals(body, receiver.accept(last)!!.message)
        assertNull(receiver.accept(last)!!.message)
        assertNull(WatchSBleFrames.Receiver().accept(last))
        assertArrayEquals(body, receiver.accept(first.copy(id = 2))?.let {
            receiver.accept(last.copy(id = 2))?.message
        })
    }

    @Test fun ackTimeoutRetriesSameFrameTwiceThenStops() = runTest {
        val attempts = mutableListOf<ByteArray>()
        val failure = runCatching {
            frames.send(1, ByteArray(8), 23, { attempts.add(it) }) { id, offset, timeout ->
                assertEquals(1L, id)
                assertEquals(6, offset)
                delay(timeout)
                false
            }
        }.exceptionOrNull()
        assertEquals("ack_timeout", failure?.message)
        assertEquals(3, attempts.size)
        assertTrue(attempts.all { it.contentEquals(attempts.first()) })
        assertEquals(9000L, currentTime)
    }

    @Test fun ackAfterRetryAdvancesToNextFrame() = runTest {
        val attempts = mutableListOf<Int>()
        frames.send(1, ByteArray(8), 23, { attempts.add(frames.decode(it, 23).offset) }) { _, _, _ ->
            attempts.size > 1
        }
        assertEquals(listOf(0, 0, 6), attempts)
    }
}
