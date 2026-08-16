package com.lightningstudio.watchrss.phone.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保留自 PhoneOobeStageTest 的 app-access 门禁断言（OOBE 阶段派生逻辑已随新引导漏斗移除）。 */
class AppAccessEnforcementTest {
    @Test
    fun `app access is not enforced while oobe is still active`() {
        assertEquals(false, shouldEnforceAppAccess(hasRequiredConsent = false, isOobeComplete = false))
        assertEquals(false, shouldEnforceAppAccess(hasRequiredConsent = true, isOobeComplete = false))
        assertEquals(true, shouldEnforceAppAccess(hasRequiredConsent = true, isOobeComplete = true))
    }
}
