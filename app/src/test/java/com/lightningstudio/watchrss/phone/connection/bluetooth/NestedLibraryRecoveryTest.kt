package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NestedLibraryRecoveryTest {
    @Test fun invalidatedNestedIoRestartsOuterUnitBeforeSending() = runBlocking {
        var connected = true
        var generation = 1L
        var searches = 0
        var domains = 0
        var sends = 0
        val order = mutableListOf<String>()
        val policy = LibraryTransferRecovery { 0 }
        policy.observe(generation)
        val recovery = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { connected }, {
            searches++; generation++; policy.observe(generation); connected = true; order += "connect"
        }, { order += "account" }, {}, { _, _ -> }, {})

        recovery.unit("notes") {
            domains++; order += "domain:$domains"
            if (domains == 1) {
                recovery.appliedProgress("notes", 1)
                connected = false
            }
            recovery.unit("asset") {
                assertTrue("nested I/O must never use the invalidated transport", connected)
                sends++; order += "send:$generation"
            }
        }

        assertEquals(listOf("domain:1", "connect", "account", "domain:2", "send:2"), order)
        assertEquals(1, searches); assertEquals(2, domains); assertEquals(1, sends)
    }

    @Test fun ownerFailureOnInvalidatedNestedIoNeverSearches() = runBlocking {
        var connected = true
        var ownerValid = true
        var searches = 0
        var sends = 0
        val ownerFailure = IllegalStateException("account changed")
        val policy = LibraryTransferRecovery { 0 }; policy.observe(1)
        val recovery = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { connected },
            { searches++ }, {}, { if (!ownerValid) throw ownerFailure }, { _, _ -> }, {})
        val failure = runCatching {
            recovery.unit("notes") {
                connected = false; ownerValid = false
                recovery.unit("asset") { sends++ }
            }
        }.exceptionOrNull()
        assertSame(ownerFailure, failure)
        assertEquals(0, searches); assertEquals(0, sends)
    }

    @Test fun cancellationBeforeInvalidatedNestedIoNeverRunsOrSearches() = runBlocking {
        var connected = true
        var searches = 0
        var sends = 0
        val policy = LibraryTransferRecovery { 0 }; policy.observe(1)
        val recovery = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { connected },
            { searches++ }, {}, {}, { _, _ -> }, {})
        val job = launch {
            recovery.unit("notes") {
                connected = false; currentCoroutineContext().cancel()
                recovery.unit("asset") { sends++ }
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, searches); assertEquals(0, sends)
    }

    @Test fun nestedLocalFailureOnHealthyTransportNeverRetries() = runBlocking {
        var searches = 0
        val localFailure = IOException("local disk failure")
        val policy = LibraryTransferRecovery { 0 }; policy.observe(1)
        val recovery = LibraryRecoveryContext(policy, LibrarySyncMode.MERGE, { true },
            { searches++ }, {}, {}, { _, _ -> }, {})
        val failure = runCatching {
            recovery.unit("notes") { recovery.unit("local") { throw localFailure } }
        }.exceptionOrNull()
        assertSame(localFailure, failure)
        assertEquals(0, searches)
    }
}
