package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class RotatingProbeCandidateWindowTest {
    @Test
    fun `candidate window respects cap and rotates omitted devices into retry`() {
        val candidates = (0 until 11).toList()

        val first = rotatingProbeCandidateWindow(candidates, maxCandidates = 9, startOffset = 0)
        val second = rotatingProbeCandidateWindow(candidates, maxCandidates = 9, startOffset = first.nextOffset)

        assertEquals((0 until 9).toList(), first.candidates)
        assertEquals(listOf(9, 10, 0, 1, 2, 3, 4, 5, 6), second.candidates)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), (first.candidates + second.candidates).toSet())
    }

    @Test
    fun `candidate window returns all devices without rotation below cap`() {
        val window = rotatingProbeCandidateWindow(listOf("a", "b"), maxCandidates = 9, startOffset = 7)

        assertEquals(listOf("a", "b"), window.candidates)
        assertEquals(0, window.startOffset)
        assertEquals(0, window.nextOffset)
    }

    @Test
    fun `candidate window normalizes persisted offset`() {
        val window = rotatingProbeCandidateWindow((0 until 10).toList(), maxCandidates = 3, startOffset = 12)

        assertEquals(listOf(2, 3, 4), window.candidates)
        assertEquals(2, window.startOffset)
        assertEquals(5, window.nextOffset)
    }

    @Test
    fun `prioritized candidates stay first while remaining candidates rotate`() {
        val candidates = (0 until 11).toList()

        val first = rotatingProbeCandidateWindow(
            candidates = candidates,
            maxCandidates = 9,
            startOffset = 0,
            prioritizedCandidates = listOf(10)
        )
        val second = rotatingProbeCandidateWindow(
            candidates = candidates,
            maxCandidates = 9,
            startOffset = first.nextOffset,
            prioritizedCandidates = listOf(10)
        )

        assertEquals(listOf(10, 0, 1, 2, 3, 4, 5, 6, 7), first.candidates)
        assertEquals(listOf(10, 8, 9, 0, 1, 2, 3, 4, 5), second.candidates)
        assertEquals(9, first.candidates.size)
        assertEquals(9, second.candidates.size)
        assertEquals((0 until 11).toSet(), (first.candidates + second.candidates).toSet())
    }

    @Test
    fun `prioritized candidates move ahead below the cap`() {
        val window = rotatingProbeCandidateWindow(
            candidates = listOf("a", "b", "c"),
            maxCandidates = 9,
            startOffset = 4,
            prioritizedCandidates = listOf("c")
        )

        assertEquals(listOf("c", "a", "b"), window.candidates)
        assertEquals(0, window.startOffset)
        assertEquals(0, window.nextOffset)
    }
}
