package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTransferRecoveryTest {
    @Test
    fun thirdDisconnectWithinTenSecondsStopsBeforeSearchingAgain() = runBlocking {
        var clock = 0L
        val searches = mutableListOf<Int>()
        val recovery = LibrarySyncRecovery(
            now = { clock }, pause = { error("a successful search needs no pause") },
            reconnect = { assertTrue(it) }, onInterrupted = {}, onSearch = { searches += it },
            onReady = {}
        )
        val failure = runCatching {
            recovery.run {
                clock += 5_000L
                throw IOException("link lost")
            }
        }.exceptionOrNull()
        assertTrue(failure is LibraryDisconnectLimit)
        assertEquals(listOf(1, 1), searches)
    }

    @Test
    fun spacedDropsAndPreviouslyFailedSearchesDoNotExhaustTheOperation() = runBlocking {
        var clock = 0L
        var units = 0
        var unavailable = true
        val waits = mutableListOf<Long>()
        val searches = mutableListOf<Int>()
        val recovery = LibrarySyncRecovery(
            now = { clock }, pause = { waits += it; clock += it },
            reconnect = { if (unavailable) { unavailable = false; throw LibraryBluetoothUnavailable() } },
            onInterrupted = { unavailable = true }, onSearch = { searches += it }, onReady = {}
        )
        val result = recovery.run {
            if (units++ < 4) {
                clock += 6_000L
                throw IOException("link lost")
            }
            "resumed"
        }
        assertEquals("resumed", result)
        assertEquals(List(4) { 8_000L }, waits)
        assertEquals(List(4) { listOf(1, 2) }.flatten(), searches)
    }

    @Test
    fun thirdFailedSearchStopsWithoutAnotherPause() = runBlocking {
        val waits = mutableListOf<Long>()
        val failed = mutableListOf<Int>()
        val recovery = LibrarySyncRecovery(
            now = { 0L }, pause = { waits += it }, reconnect = { throw IOException("no peer") },
            onInterrupted = {}, onSearch = {}, onSearchFailed = { failed += it }, onReady = { error("no peer") }
        )
        val result = runCatching { recovery.search(confirmAccount = false) }.exceptionOrNull()
        assertTrue(result is LibraryReconnectLimit)
        assertEquals(listOf(1, 2, 3), failed)
        assertEquals(listOf(8_000L, 8_000L), waits)
    }

    @Test
    fun cancellationAndProtocolRejectionNeverStartAReconnect() = runBlocking {
        val recovery = LibrarySyncRecovery(
            now = { 0L }, pause = { error("no pause expected") }, reconnect = { error("no reconnect expected") },
            onInterrupted = { error("no transport failure expected") }, onSearch = {}, onReady = {}
        )
        val cancellation = runCatching { recovery.run { throw CancellationException("cancelled") } }.exceptionOrNull()
        val rejection = runCatching { recovery.run { throw IllegalArgumentException("socket closed") } }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertTrue(rejection is IllegalArgumentException)
    }
}
