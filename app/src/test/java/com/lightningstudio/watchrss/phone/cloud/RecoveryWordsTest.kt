package com.lightningstudio.watchrss.phone.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryWordsTest {
    @Test
    fun roundTripsTwentyFourRecoveryWords() {
        val entropy = ByteArray(32) { index -> (index * 7).toByte() }
        val words = RecoveryWords.encode(entropy)

        assertEquals(24, words.size)
        assertArrayEquals(entropy, RecoveryWords.decode(words))
        assertArrayEquals(entropy, RecoveryWords.decode(RecoveryWords.parse(words.joinToString(" "))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsChecksumMismatch() {
        val words = RecoveryWords.encode(ByteArray(32) { 1 }).toMutableList()
        words[23] = RecoveryWords.encode(ByteArray(32) { 2 })[23]
        RecoveryWords.decode(words)
    }
}
