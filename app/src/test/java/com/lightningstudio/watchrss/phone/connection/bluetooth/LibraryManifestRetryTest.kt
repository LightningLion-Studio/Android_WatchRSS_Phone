package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryManifestRetryTest {
    private fun frame(sentAt: Long, head: String = "article") = JSONObject()
        .put("version", 14).put("action", "syncLibrary").put("phase", "manifest")
        .put("sentAt", sentAt).put("articleManifest", JSONArray().put(JSONObject().put("articleId", head)))

    @Test fun identicalResponseFailureAcrossNewTransportsStopsAtThree() = runBlocking {
        withContext(LibraryManifestRetry()) {
            repeat(3) { round ->
                val failure = runCatching {
                    exchangeManifest(listOf(frame(round.toLong())), write = { _, _ -> }) {
                        it?.reading(0)
                        throw EOFException()
                    }
                }.exceptionOrNull()
                if (round == 2) assertTrue(failure is RepeatedLibraryManifestFailure)
                else assertTrue(failure is EOFException)
            }
        }
    }

    @Test fun differentBoundaryContentAndSuccessEachResetTheCount() = runBlocking {
        withContext(LibraryManifestRetry()) {
            val windows = listOf(frame(0), frame(1), frame(2), frame(3,"new"), frame(4,"new"))
            windows.forEachIndexed { index, frame ->
                val failure = runCatching {
                    exchangeManifest(listOf(frame), write = { _, _ -> }) {
                        it?.reading(if (index < 2) 0 else 1)
                        throw IOException()
                    }
                }.exceptionOrNull()
                assertTrue(failure is IOException)
            }
            exchangeManifest(listOf(frame(5,"new")), write = { _, _ -> }) { JSONObject() }
            val replay = runCatching {
                exchangeManifest(listOf(frame(6,"new")), write = { _, _ -> }) {
                    it?.reading(1)
                    throw IOException()
                }
            }.exceptionOrNull()
            assertTrue(replay is IOException)
        }
    }

    @Test fun explicitProtocolErrorsAreNeverReclassified() = runBlocking {
        withContext(LibraryManifestRetry()) {
            repeat(4) {
                val failed = runCatching {
                    exchangeManifest(listOf(frame(it.toLong())), write = { _, _ -> throw ProtocolException() }) {
                        error("read attempted after failed write")
                    }
                }.exceptionOrNull()
                assertTrue(failed is ProtocolException)
            }
        }
    }

    @Test fun fullInventoryBelowLegacyLimitUsesBoundedManifestFrames() {
        val rows = JSONArray()
        repeat(2710) { index ->
            rows.put(JSONObject().put("articleId", index.toString()).put("contentHash", "a".repeat(64))
                .put("bodyHash", "b".repeat(64)).put("metadataHash", "c".repeat(64))
                .put("chunkHashes", JSONArray().put("e".repeat(64))))
        }
        val request = frame(42).put("articleManifest", rows).put("fullSnapshot", true)
            .put("fallbackReason", "projectionChanged").put("deviceId", "test")
        val original = BluetoothSyncProtocol.encodedSize(request)
        assertTrue(original in 512_000..1_900_000)
        val split = LibrarySyncPayload.buildManifestFrames(request)
        assertTrue(split.size > 2)
        assertTrue(split.all { BluetoothSyncProtocol.encodedSize(it) < 200 * 1024 })
        val combined = LibrarySyncPayload.combineManifestFrames(split)
        assertEquals("projectionChanged", combined.getString("fallbackReason"))
        assertEquals(rows.toString(), combined.getJSONArray("articleManifest").toString())
    }
}
