@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenPairingRequestTest {
    private val target = PhoneBluetoothWatchDevice("watch", "AA:BB:CC:DD:EE:FF", 1)
    private fun opened() = JSONObject().put("success", true).put("action", "openPairing").put("opened", true)
    private fun unavailable(reason: WatchQrNotOpenedReason) = WatchQrRequest.NotOpened(reason)

    @Test fun openedRequiresAnExplicitReceiptAndSendsOnlyTheNewActionToTheSelectedWatch() = runTest {
        var selections = 0; var sends = 0
        val result = OpenPairingRequest({ selections++; target }) { device, request ->
            sends++; assertSame(target, device)
            assertEquals(setOf("version", "action"), request.keys().asSequence().toSet())
            assertEquals(1, request.getInt("version")); assertEquals("openPairing", request.getString("action"))
            opened()
        }.send()
        assertEquals(WatchQrRequest.Opened, result); assertEquals(1, selections); assertEquals(1, sends)
    }
    @Test fun noPairedWatchDoesNotSendAnything() = runTest {
        var sends = 0
        val result = OpenPairingRequest({ throw WatchQrUnavailable(WatchQrNotOpenedReason.NOT_PAIRED) }) { _, _ -> sends++; opened() }.send()
        assertEquals(unavailable(WatchQrNotOpenedReason.NOT_PAIRED), result); assertEquals(0, sends)
    }
    @Test fun bluetoothOffDoesNotSendAnything() = runTest {
        var sends = 0
        val result = OpenPairingRequest({ throw WatchQrUnavailable(WatchQrNotOpenedReason.BLUETOOTH_OFF) }) { _, _ -> sends++; opened() }.send()
        assertEquals(unavailable(WatchQrNotOpenedReason.BLUETOOTH_OFF), result); assertEquals(0, sends)
    }
    @Test fun missingPermissionOrItsRevocationNeverTriesARequestAgainOrLeaksPlatformErrors() = runTest {
        var sends = 0
        val before = OpenPairingRequest({ throw WatchQrUnavailable(WatchQrNotOpenedReason.PERMISSION_MISSING) }) { _, _ -> sends++; opened() }.send()
        assertEquals(unavailable(WatchQrNotOpenedReason.PERMISSION_MISSING), before); assertEquals(0, sends)
        val during = OpenPairingRequest({ target }) { _, _ -> sends++; throw SecurityException("raw platform details") }.send()
        assertEquals(unavailable(WatchQrNotOpenedReason.PERMISSION_MISSING), during); assertEquals(1, sends)
        assertFalse(during.toString().contains("raw platform details"))
    }
    @Test fun unresponsiveWatchAndOpenedFalseNeverBecomeSuccess() = runTest {
        val lost = OpenPairingRequest({ target }) { _, _ -> throw IOException("private platform error") }.send()
        assertEquals(unavailable(WatchQrNotOpenedReason.NO_RESPONSE), lost)
        for (reply in listOf(opened().put("opened", false), opened().put("success", "true"),
            opened().put("action", "pong"), opened().put("opened", "true"), JSONObject())) {
            assertEquals(unavailable(WatchQrNotOpenedReason.NO_RESPONSE), OpenPairingRequest({ target }) { _, _ -> reply }.send())
        }
    }
    @Test fun legacyUnsupportedRepliesAreNotOpenedAndNeverRetried() = runTest {
        for (reply in listOf(JSONObject().put("success", false).put("errorCode", "unsupported_action"),
            JSONObject().put("success", false).put("error", "unknown_action"),
            JSONObject().put("success", false).put("message", "未知蓝牙同步动作：openPairing"),
            JSONObject().put("success", false).put("message", "当前前台自动同步不支持此操作，请在手表上打开对应连接页面"))) {
            var sends = 0
            assertEquals(unavailable(WatchQrNotOpenedReason.UNSUPPORTED_WATCH), OpenPairingRequest({ target }) { _, _ -> sends++; reply }.send())
            assertEquals(1, sends)
        }
    }
    @Test fun entireAttemptIncludingSelectionExpiresAtSixSecondsAndCancelsThePendingOperation() = runTest {
        for (hangAtSelection in listOf(true, false)) {
            var cancelled = false; var sends = 0
            val request = OpenPairingRequest({
                if (hangAtSelection) try { awaitCancellation() } finally { cancelled = true }
                target
            }) { _, _ -> sends++; try { awaitCancellation() } finally { cancelled = true } }
            val result = async { request.send() }; runCurrent(); advanceTimeBy(5_999); runCurrent()
            assertFalse(result.isCompleted); advanceTimeBy(1); runCurrent()
            assertEquals(unavailable(WatchQrNotOpenedReason.NO_RESPONSE), result.await())
            assertTrue(cancelled); assertEquals(if (hangAtSelection) 0 else 1, sends)
        }
    }
    @Test fun cancellationIsPreservedInsteadOfReportingANewFailureAfterLeavingThePage() = runTest {
        var result: WatchQrRequest? = null
        val job = launch { result = OpenPairingRequest({ target }) { _, _ -> awaitCancellation() }.send() }
        runCurrent(); job.cancelAndJoin(); assertNull(result)
    }
}
