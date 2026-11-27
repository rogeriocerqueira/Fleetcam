package dev.fleetcam.health.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HealthThresholdsTest {

    @Test
    fun `defaults are valid`() {
        HealthThresholds.DEFAULTS // init would throw otherwise
    }

    @Test
    fun `percent written instead of fraction is refused with a clear message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            HealthThresholds.DEFAULTS.copy(sdLowSpaceRatio = 5.0)
        }
        assertTrue("fraction" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `values that would flap or flood alerts are refused`() {
        assertFailsWith<IllegalArgumentException> { HealthThresholds.DEFAULTS.copy(offlineAfter = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { HealthThresholds.DEFAULTS.copy(clockSkewTolerance = Duration.ofSeconds(5)) }
        assertFailsWith<IllegalArgumentException> { HealthThresholds.DEFAULTS.copy(rebootLoopCount = 1) }
    }
}
