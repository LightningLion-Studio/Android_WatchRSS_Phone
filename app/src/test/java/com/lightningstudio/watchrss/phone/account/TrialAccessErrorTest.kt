package com.lightningstudio.watchrss.phone.account

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class TrialAccessErrorTest {
    @Test
    fun mapsUsedAndDeviceConflictErrors() {
        assertEquals(
            "该账号已领取过试用",
            trialAccessErrorMessage(PhoneAccountHttpException(409, "{\"error\":\"trial_already_used\"}"))
        )
        assertEquals(
            "该账号的试用已绑定其他手机",
            trialAccessErrorMessage(PhoneAccountHttpException(409, "{\"error_code\":\"trial_device_mismatch\"}"))
        )
    }

    @Test
    fun mapsNetworkFailure() {
        assertEquals(
            "网络连接失败，请检查网络后重试",
            trialAccessErrorMessage(IOException("offline"))
        )
    }
}
