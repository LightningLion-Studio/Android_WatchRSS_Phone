package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

sealed interface WatchQrRequest {
    data object Sending : WatchQrRequest
    data object Opened : WatchQrRequest
    data class NotOpened(val reason: WatchQrNotOpenedReason) : WatchQrRequest
}

enum class WatchQrNotOpenedReason(val message: String) {
    NOT_PAIRED("未找到已配对手表，请在手表上手动打开配对二维码"),
    BLUETOOTH_OFF("蓝牙未开启，请在手表上手动打开配对二维码"),
    NO_RESPONSE("手表未响应，请在手表上手动打开配对二维码"),
    UNSUPPORTED_WATCH("旧版手表不支持自动打开二维码，请在手表上手动打开"),
    PERMISSION_MISSING("未授予蓝牙权限，请在手表上手动打开配对二维码")
}

internal class WatchQrUnavailable(val reason: WatchQrNotOpenedReason) : IllegalStateException()

/** Optional notification only. This action deliberately carries no account or QR secrets. */
internal class OpenPairingRequest(
    private val selectTarget: suspend () -> PhoneBluetoothWatchDevice,
    private val exchange: suspend (PhoneBluetoothWatchDevice, JSONObject) -> JSONObject
) {
    suspend fun send(): WatchQrRequest = try {
        withTimeout(TIMEOUT_MS) {
            val target = selectTarget()
            val request = JSONObject().put("version", 1).put("action", BluetoothSyncProtocol.ACTION_OPEN_PAIRING)
            parseResponse(exchange(target, request))
        }
    } catch (_: TimeoutCancellationException) {
        WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: WatchQrUnavailable) {
        WatchQrRequest.NotOpened(unavailable.reason)
    } catch (_: SecurityException) {
        WatchQrRequest.NotOpened(WatchQrNotOpenedReason.PERMISSION_MISSING)
    } catch (_: Exception) {
        WatchQrRequest.NotOpened(WatchQrNotOpenedReason.NO_RESPONSE)
    }

    companion object {
        const val TIMEOUT_MS = 6_000L
        internal fun parseResponse(response: JSONObject): WatchQrRequest {
            if (response.opt("success") == true && response.opt("action") == BluetoothSyncProtocol.ACTION_OPEN_PAIRING &&
                response.opt("opened") == true) return WatchQrRequest.Opened
            val code = response.optString("errorCode").ifBlank { response.optString("error") }
            val message = response.optString("message")
            val unsupported = code == "unsupported_action" || code == "unknown_action" ||
                (response.opt("success") == false &&
                    (message.contains("未知") && message.contains("动作") || message.contains("不支持此操作") || message.contains("当前同步操作尚未接入")))
            return WatchQrRequest.NotOpened(if (unsupported) WatchQrNotOpenedReason.UNSUPPORTED_WATCH else WatchQrNotOpenedReason.NO_RESPONSE)
        }
    }
}
