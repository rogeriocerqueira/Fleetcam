package dev.fleetcam.health.domain

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.events.SdCardSnapshot
import dev.fleetcam.health.presentation.toSupportText
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceHealthEvaluatorTest {

    private val evaluator = DeviceHealthEvaluator(HealthThresholds.DEFAULTS)
    private val t0 = Instant.parse("2026-11-20T12:00:00Z")
    private val healthySd = SdCardSnapshot(totalBytes = 64_000_000_000, freeBytes = 20_000_000_000, writeErrorsSinceBoot = 0, readOnly = false)

    private fun hb(
        at: Instant,
        uptime: Long = 3600,
        sd: SdCardSnapshot = healthySd,
        clockOffset: Duration = Duration.ZERO,
        firmware: String = "2.2.0",
    ) = HeartbeatReceived(
        messageId = "hb-${at.epochSecond}", deviceId = "cam-0001", firmwareVersion = firmware,
        deviceTime = at.minus(clockOffset), deviceSentAt = at.minus(clockOffset), receivedAt = at,
        uptimeSeconds = uptime, sdCard = sd, network = "LTE", signalDbm = -90, pendingUploads = 0,
    )

    @Test
    fun `first healthy heartbeat creates an ACTIVE device`() {
        val eval = evaluator.onHeartbeat(null, hb(t0))

        assertEquals(DeviceState.ACTIVE, eval.snapshot.state)
        assertEquals(StateTransition(null, DeviceState.ACTIVE, "primeiro heartbeat"), eval.transition)
        assertTrue(eval.opened.isEmpty())
    }

    @Test
    fun `read-only SD card degrades the device and resolves when the card is replaced`() {
        val active = evaluator.onHeartbeat(null, hb(t0)).snapshot

        val broken = evaluator.onHeartbeat(active, hb(t0.plusSeconds(60), sd = healthySd.copy(readOnly = true)))
        assertEquals(DeviceState.DEGRADED, broken.snapshot.state)
        assertEquals(listOf(IssueType.SD_CARD_READ_ONLY), broken.opened.map { it.type })

        val fixed = evaluator.onHeartbeat(broken.snapshot, hb(t0.plusSeconds(120), uptime = 10))
        assertEquals(DeviceState.ACTIVE, fixed.snapshot.state)
        assertEquals(setOf(IssueType.SD_CARD_READ_ONLY), fixed.resolved)
    }

    @Test
    fun `issue already open is not opened twice`() {
        val readOnly = healthySd.copy(readOnly = true)
        val first = evaluator.onHeartbeat(null, hb(t0, sd = readOnly))
        val second = evaluator.onHeartbeat(first.snapshot, hb(t0.plusSeconds(60), uptime = 3660, sd = readOnly))

        assertTrue(second.opened.isEmpty())
        assertNull(second.transition)
    }

    @Test
    fun `three reboots inside the window is a reboot loop`() {
        var s = evaluator.onHeartbeat(null, hb(t0, uptime = 500)).snapshot
        var last: Evaluation? = null
        // uptime goes 500 -> 20 -> 20 -> 20: three resets, five minutes apart
        for (i in 1..3) {
            last = evaluator.onHeartbeat(s, hb(t0.plusSeconds(300L * i), uptime = 20))
            s = last.snapshot
        }

        assertEquals(3, s.rebootsInWindow)
        assertTrue(IssueType.REBOOT_LOOP in s.openIssues)
        assertEquals(DeviceState.DEGRADED, last!!.transition!!.to)
    }

    @Test
    fun `clock skew is detected from batch send time, not from message age`() {
        // Firmware 2.3.1 bug: local time (UTC-3) reported as UTC
        val eval = evaluator.onHeartbeat(null, hb(t0, clockOffset = Duration.ofHours(3), firmware = "2.3.1"))

        val issue = eval.opened.single()
        assertEquals(IssueType.CLOCK_SKEW, issue.type)
        val text = issue.detail.toSupportText()
        assertTrue("atrasado ~180 min" in text, text)
        // A warning the backend already compensates for must not bury real failures in the DEGRADED list.
        assertEquals(DeviceState.ACTIVE, eval.snapshot.state)
    }

    @Test
    fun `warnings stay visible but only critical issues degrade the device`() {
        val warnings = healthySd.copy(writeErrorsSinceBoot = 15, freeBytes = 1_000_000_000) // write errors + low space
        val eval = evaluator.onHeartbeat(null, hb(t0, sd = warnings))

        assertEquals(setOf(IssueType.SD_CARD_WRITE_ERRORS, IssueType.SD_CARD_LOW_SPACE), eval.snapshot.openIssues)
        assertEquals(DeviceState.ACTIVE, eval.snapshot.state)

        val critical = evaluator.onHeartbeat(eval.snapshot, hb(t0.plusSeconds(60), uptime = 3660, sd = warnings.copy(readOnly = true)))
        assertEquals(DeviceState.DEGRADED, critical.snapshot.state)
        assertEquals(StateTransition(DeviceState.ACTIVE, DeviceState.DEGRADED, "abertos=[SD_CARD_READ_ONLY]"), critical.transition)
    }

    @Test
    fun `buffered backlog with correct clock is NOT clock skew`() {
        // Message produced 2h ago (device offline), sent now: old deviceTime but deviceSentAt ~= receivedAt
        val old = hb(t0).copy(deviceTime = t0.minus(Duration.ofHours(2)))

        val eval = evaluator.onHeartbeat(null, old)

        assertTrue(eval.opened.none { it.type == IssueType.CLOCK_SKEW })
    }

    @Test
    fun `out-of-order heartbeat refreshes lastSeen but never rolls state back`() {
        val current = evaluator.onHeartbeat(null, hb(t0, sd = healthySd.copy(readOnly = true))).snapshot

        val stale = hb(t0.plusSeconds(30)).copy(deviceTime = t0.minusSeconds(600)) // healthy, but older
        val eval = evaluator.onHeartbeat(current, stale)

        assertEquals(DeviceState.DEGRADED, eval.snapshot.state)
        assertEquals(t0.plusSeconds(30), eval.snapshot.lastSeenAt)
        assertTrue(eval.resolved.isEmpty())
    }

    @Test
    fun `silent device goes OFFLINE and comes back with next heartbeat`() {
        val active = evaluator.onHeartbeat(null, hb(t0)).snapshot

        assertNull(evaluator.onOfflineCheck(active, t0.plus(Duration.ofMinutes(4))))

        val offline = evaluator.onOfflineCheck(active, t0.plus(Duration.ofMinutes(6)))!!
        assertEquals(DeviceState.OFFLINE, offline.snapshot.state)
        assertEquals(IssueType.OFFLINE, offline.opened.single().type)

        val back = evaluator.onHeartbeat(offline.snapshot, hb(t0.plus(Duration.ofMinutes(10)), uptime = 4200))
        assertEquals(DeviceState.ACTIVE, back.snapshot.state)
        assertEquals(setOf(IssueType.OFFLINE), back.resolved)
    }
}
