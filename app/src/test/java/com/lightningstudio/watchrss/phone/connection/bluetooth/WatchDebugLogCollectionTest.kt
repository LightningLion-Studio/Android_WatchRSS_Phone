package com.lightningstudio.watchrss.phone.connection.bluetooth

import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncStage
import com.lightningstudio.watchrss.phone.data.telemetry.BtSyncError
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class WatchDebugLogCollectionTest {
    @Test fun legacyAndStructuredForegroundRefusalsProduceRetryHint() {
        assertTrue(watchDebugLogForegroundRestricted(JSONObject().put(
            "message", "当前前台自动同步不支持此操作，请在手表上打开对应连接页面"
        )))
        assertTrue(watchDebugLogForegroundRestricted(JSONObject().put("errorCode", "foreground_required")))
        assertFalse(watchDebugLogForegroundRestricted(JSONObject().put("errorCode", "account_required")))
        assertTrue(WatchDebugLogForegroundRestricted().message!!.contains("打开蓝牙连接页"))
    }

    @Test fun collectionRecordsSafeVersionAndFailureCode() {
        val context = RuntimeEnvironment.getApplication()
        val log = BluetoothDebugLog(context)
        val version = watchAppVersion(JSONObject().put("libraryPeer", JSONObject().put("appVersion", "2.1.0-130")))
        assertEquals("2.1.0-130", version)
        log.recordWatchLogCollection(BluetoothDebugLog.WatchLogStage.PULL, BluetoothDebugLog.WatchLogCode.FOREGROUND_RESTRICTED, version)
        log.recordWatchLogCollection(BluetoothDebugLog.WatchLogStage.PULL, BluetoothDebugLog.WatchLogCode.PEER_REJECTED, "2.1.0-private@example.org")
        log.recordSyncFailure(BtSyncStage.CONNECT, BtSyncError.TIMEOUT, version, 130)
        val text = log.snapshot()
        assertTrue(text.contains("phoneAppVersion=${BuildConfig.VERSION_NAME}"))
        assertTrue(text.contains("failureStage=\"pull\" errorCode=\"foreground_restricted\""))
        assertTrue(text.contains("watchAppVersion=\"2.1.0-130\""))
        assertTrue(text.contains("watchAppVersion=null"))
        assertTrue(text.contains("failureStage=\"connect\" errorCode=\"timeout\""))
        assertTrue(text.contains("watchAppVersionCode=130"))
        assertFalse(text.contains("private@example.org"))
    }
}
