package dev.fleetcam.simulator

import kotlin.test.Test
import kotlin.test.assertEquals

class SimulatorConfigTest {

    @Test
    fun `parses fault spec`() {
        assertEquals(
            mapOf(Fault.SD_CARD_DEGRADING to 3, Fault.FIRMWARE_CLOCK_SKEW to 1),
            SimulatorConfig.parseFaults("sd_card_degrading:3, firmware_clock_skew"),
        )
    }

    @Test
    fun `empty spec means no faults`() {
        assertEquals(emptyMap(), SimulatorConfig.parseFaults(""))
    }
}
