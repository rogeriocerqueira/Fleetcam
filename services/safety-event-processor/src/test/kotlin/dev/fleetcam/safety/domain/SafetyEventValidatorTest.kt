package dev.fleetcam.safety.domain

import dev.fleetcam.contracts.events.SafetyEventReceived
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafetyEventValidatorTest {

    private val now = Instant.parse("2026-11-20T15:00:00Z")
    private val valid = SafetyEventReceived(
        messageId = "m1", deviceId = "cam-1", firmwareVersion = "2.2.0",
        deviceTime = now, deviceSentAt = now, receivedAt = now,
        type = "PHONE_USAGE", confidence = 0.91, latitude = -12.97, longitude = -38.50, speedKmh = 62.0, clipId = "clip-1",
    )

    @Test
    fun `valid event has no violations`() {
        assertTrue(SafetyEventValidator.violations(valid).isEmpty())
    }

    @Test
    fun `GPS without fix is rejected`() {
        assertEquals(listOf("GPS without fix (0,0)"), SafetyEventValidator.violations(valid.copy(latitude = 0.0, longitude = 0.0)))
    }

    @Test
    fun `collects every violation, not only the first`() {
        val v = SafetyEventValidator.violations(valid.copy(type = "ALIEN", confidence = 1.7, speedKmh = -3.0))
        assertEquals(3, v.size, v.toString())
    }
}
