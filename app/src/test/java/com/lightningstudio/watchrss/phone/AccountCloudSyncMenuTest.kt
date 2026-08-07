package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.cloud.CloudMemberState
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountCloudSyncMenuTest {
    @Test
    fun `cloud sync entry is hidden from release builds`() {
        assertEquals(false, shouldShowCloudSyncEntry(isDebugBuild = false))
        assertEquals(true, shouldShowCloudSyncEntry(isDebugBuild = true))
    }

    @Test
    fun `enabled account is summarized without exposing all settings`() {
        assertEquals(
            "已启用 · 端到端加密",
            cloudSyncMenuSummary(member = activeMember(), hasLocalKey = true)
        )
    }

    @Test
    fun `writable account without local key points to setup or recovery`() {
        assertEquals(
            "云备份可用 · 等待启用或恢复",
            cloudSyncMenuSummary(member = activeMember(), hasLocalKey = false)
        )
    }

    @Test
    fun `expired account reports read only recovery state`() {
        assertEquals(
            "已启用 · 当前为只读恢复期",
            cloudSyncMenuSummary(
                member = activeMember().copy(writable = false),
                hasLocalKey = true
            )
        )
    }

    private fun activeMember() = CloudMemberState(
        plan = "member",
        active = true,
        writable = true,
        readable = true,
        quotaBytes = 1_073_741_824,
        usedBytes = 0,
        reservedBytes = 0,
        retentionDays = 30,
        readOnlyAt = null,
        deleteAfter = null
    )
}
