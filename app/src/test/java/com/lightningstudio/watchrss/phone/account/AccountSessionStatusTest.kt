package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSessionStatusTest {
    @Test fun cloudEntryDistinguishesSignOutExpiryAndSameAccountRelogin() {
        val current = PhoneAccountSession("owner", "1**", "old", "refresh", 1_000)
        assertEquals(AccountSessionStatus.SIGNED_OUT, accountSessionStatus(null, null, 1_000))
        assertEquals(AccountSessionStatus.USABLE, accountSessionStatus(current, null, 999))
        assertEquals(AccountSessionStatus.EXPIRED, accountSessionStatus(current, null, 1_000))
        assertEquals(AccountSessionStatus.EXPIRED, accountSessionStatus(current, "owner", 999))
        assertEquals(AccountSessionStatus.EXPIRED, accountSessionStatus(current.copy(authenticationPaused = true), null, 999))
        val renewed = current.copy(accessToken = "new", expiresAtMillis = 2_000)
        assertEquals(AccountSessionStatus.USABLE, accountSessionStatus(renewed, null, 1_000))
    }
}
