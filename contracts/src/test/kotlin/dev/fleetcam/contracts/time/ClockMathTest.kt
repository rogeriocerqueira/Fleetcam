package dev.fleetcam.contracts.time

import dev.fleetcam.contracts.events.SafetyEventReceived
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockMathTest {

    private val serverNow = Instant.parse("2026-11-20T15:00:00Z")

    private fun event(deviceTime: Instant, deviceSentAt: Instant, receivedAt: Instant) = SafetyEventReceived(
        messageId = "m1", deviceId = "cam-1", firmwareVersion = "2.3.1",
        deviceTime = deviceTime, deviceSentAt = deviceSentAt, receivedAt = receivedAt,
        type = "DROWSINESS", confidence = 0.9, latitude = -12.97, longitude = -38.5, speedKmh = 80.0, clipId = "c1",
    )

    @Test
    fun `device clock 3h behind does not inflate end-to-end latency`() {
        val skew = Duration.ofHours(3)
        // Event happened 2s before sending, gateway received 1s later, processed 4s after that.
        val e = event(
            deviceTime = serverNow.minus(skew).minusSeconds(2),
            deviceSentAt = serverNow.minus(skew),
            receivedAt = serverNow.plusSeconds(1),
        )

        assertEquals(Duration.ofSeconds(6), ClockMath.endToEndLatency(e, serverNow.plusSeconds(5)))
    }

    @Test
    fun `buffered events keep their real age even with correct clock`() {
        // Device was offline for 10 minutes and flushed its buffer on reconnect.
        val e = event(
            deviceTime = serverNow.minus(Duration.ofMinutes(10)),
            deviceSentAt = serverNow,
            receivedAt = serverNow,
        )

        assertEquals(Duration.ofMinutes(10), ClockMath.deviceBufferDelay(e))
        assertEquals(Duration.ZERO, ClockMath.estimatedClockOffset(e))
    }

    @Test
    fun `corrected event time moves skewed events back to server clock`() {
        val skew = Duration.ofHours(3)
        val realEventTime = serverNow.minusSeconds(30)
        val e = event(
            deviceTime = realEventTime.minus(skew),
            deviceSentAt = serverNow.minus(skew),
            receivedAt = serverNow,
        )

        val corrected = ClockMath.correctedEventTime(e, tolerance = Duration.ofMinutes(5))

        assertEquals(realEventTime, corrected.eventTime)
        assertEquals(skew, corrected.offsetApplied)
    }

    @Test
    fun `small offsets are treated as network jitter and not corrected`() {
        val e = event(serverNow.minusSeconds(3), serverNow.minusSeconds(2), serverNow)

        val corrected = ClockMath.correctedEventTime(e, tolerance = Duration.ofMinutes(5))

        assertEquals(e.deviceTime, corrected.eventTime)
        assertEquals(Duration.ZERO, corrected.offsetApplied)
    }
}
