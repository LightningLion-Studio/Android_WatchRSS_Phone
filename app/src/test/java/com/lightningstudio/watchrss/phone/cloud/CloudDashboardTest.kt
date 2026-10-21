package com.lightningstudio.watchrss.phone.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudDashboardTest {
    private val member = CloudMemberState("member", true, true, true, 1000, 300, 200, 30, null, null)
    @Test fun `only stale session errors trigger possession refresh, revoked device never reclaims`() {
        assertTrue(isRefreshableCloudDeviceSessionError("device_session_revoked"))
        assertTrue(isRefreshableCloudDeviceSessionError("device_session_expired"))
        assertFalse(isRefreshableCloudDeviceSessionError("device_revoked"))
        assertFalse(isRefreshableCloudDeviceSessionError("app_access_expired"))
        assertFalse(isRefreshableCloudDeviceSessionError("device_mismatch"))
    }
    @Test fun `pause survives reconstruction and resume reopens transfer gate`() {
        var persisted = false
        val first = CloudSyncPauseGate(persisted) { persisted = it }
        first.setPaused(true)
        assertTrue(first.state.value)
        val recreated = CloudSyncPauseGate(persisted) { persisted = it }
        assertTrue(recreated.state.value)
        recreated.setPaused(false)
        assertFalse(recreated.state.value)
        assertFalse(persisted)
    }
    @Test fun `persistence failure cannot display a successful pause`() {
        val gate = CloudSyncPauseGate(false) { throw IllegalStateException("disk failure") }
        runCatching { gate.setPaused(true) }
        assertFalse(gate.state.value)
    }
    @Test fun `missing key needs authorization and free accounts keep syncing`() {
        assertEquals("授权此手机", cloudDashboardPresentation(true, false, false, CloudSyncPhase.IDLE, member).actionLabel)
        assertEquals("同步完成", cloudDashboardPresentation(true, true, false, CloudSyncPhase.COMPLETE,
            member.copy(writable = false, active = false, quotaBytes = 15 * 1024 * 1024)).status)
        assertEquals("登录账号", cloudDashboardPresentation(false, true, false, CloudSyncPhase.COMPLETE, member).actionLabel)
    }
    @Test fun `pause distinguishes in flight transaction from stopped transfers`() {
        assertEquals("正在暂停", cloudDashboardPresentation(true, true, true, CloudSyncPhase.UPLOADING, member).status)
        assertEquals("同步暂停", cloudDashboardPresentation(true, true, true, CloudSyncPhase.PAUSED, member).status)
    }
    @Test fun `device authorization errors request identity verification rather than network retry`() {
        assertEquals("验证设备身份", cloudDashboardPresentation(true, true, false, CloudSyncPhase.NEEDS_RECOVERY, member).actionLabel)
    }
    @Test fun `dashboard reflects transfer completion and pending state distinctly`() {
        assertEquals("等待同步", cloudDashboardPresentation(true, true, false, CloudSyncPhase.IDLE, member).status)
        for (phase in cloudActivePhases) {
            assertEquals("正在同步", cloudDashboardPresentation(true, true, false, phase, member).status)
        }
        assertEquals("同步完成", cloudDashboardPresentation(true, true, false, CloudSyncPhase.COMPLETE, member).status)
    }
    @Test fun `waiting states and failures never imply completed sync`() {
        assertEquals("等待同步", cloudDashboardPresentation(true, true, false, CloudSyncPhase.WAITING_FOR_WIFI, member).status)
        assertEquals("重试同步", cloudDashboardPresentation(true, true, false, CloudSyncPhase.ERROR, member).actionLabel)
    }
}
