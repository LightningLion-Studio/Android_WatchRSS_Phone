package com.lightningstudio.watchrss.phone.test

import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule

object ScreenshotTestPermissions {

    /**
     * 返回截图测试需要的权限 grant rule。
     *
     * 在 Android 12+ 上，App 启动后点击“同步手表”会检查 [Manifest.permission.BLUETOOTH_CONNECT]，
     * 预先授权可避免权限弹窗阻塞测试与截图。
     */
    fun grantRule(): GrantPermissionRule {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        return if (permissions.isEmpty()) {
            // GrantPermissionRule 不接受空数组，返回一个无实际授权的 no-op rule
            GrantPermissionRule.grant()
        } else {
            GrantPermissionRule.grant(*permissions.toTypedArray())
        }
    }
}
