package com.phonelink.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun `generateMobileDeviceId has expected format`() {
        val id = DeviceIdentity.generateMobileDeviceId()

        assertTrue(id.startsWith("mobile-"))
        assertEquals(7 + 32, id.length)
        assertTrue(id.substringAfter("mobile-").all { it.isLetterOrDigit() })
    }

    @Test
    fun `generateMobileDeviceId produces unique ids`() {
        val ids = (1..50).map { DeviceIdentity.generateMobileDeviceId() }

        assertEquals(50, ids.distinct().size)
    }
}