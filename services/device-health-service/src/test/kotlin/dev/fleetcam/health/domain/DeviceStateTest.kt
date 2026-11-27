package dev.fleetcam.health.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceStateTest {

    @Test
    fun `state is derived from open issues`() {
        assertEquals(DeviceState.ACTIVE, DeviceState.from(emptySet()))
        assertEquals(DeviceState.ACTIVE, DeviceState.from(setOf(IssueType.CLOCK_SKEW, IssueType.SD_CARD_LOW_SPACE)))
        assertEquals(DeviceState.DEGRADED, DeviceState.from(setOf(IssueType.CLOCK_SKEW, IssueType.REBOOT_LOOP)))
        assertEquals(DeviceState.OFFLINE, DeviceState.from(setOf(IssueType.OFFLINE, IssueType.SD_CARD_READ_ONLY)))
    }
}
