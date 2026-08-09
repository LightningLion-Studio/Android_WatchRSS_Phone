package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TotpEnrollmentTransitionTest {
    @Test
    fun `successful enrollment invalidates the previous session after confirmation`() = runBlocking {
        val events = mutableListOf<String>()

        completeTotpEnrollmentTransition(
            confirmEnrollment = { events += "confirmed" },
            invalidateSession = { events += "invalidated" }
        )

        assertEquals(listOf("confirmed", "invalidated"), events)
    }

    @Test
    fun `failed enrollment keeps the current session`() {
        val expected = IllegalStateException("invalid code")
        var invalidated = false

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                completeTotpEnrollmentTransition(
                    confirmEnrollment = { throw expected },
                    invalidateSession = { invalidated = true }
                )
            }
        }

        assertSame(expected, thrown)
        assertFalse(invalidated)
    }
}
