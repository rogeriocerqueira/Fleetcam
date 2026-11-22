package dev.fleetcam.contracts.time

import dev.fleetcam.contracts.events.DeviceMessage
import java.time.Duration
import java.time.Instant

/**
 * Time arithmetic that is robust to wrong device clocks.
 *
 * Rule of thumb: only subtract timestamps that come from the same clock.
 * - device buffer delay  = deviceSentAt - deviceTime   (device clock - device clock)
 * - server delay         = <now>       - receivedAt   (server clock - server clock)
 * - clock offset (est.)  = receivedAt  - deviceSentAt (server clock - device clock)
 *
 * The clock offset estimate includes network transit time (typically < 2s), which is
 * negligible compared to the skews we care about (minutes to hours).
 */
object ClockMath {

    /** Positive = device clock is behind the server; negative = device clock is ahead. */
    fun estimatedClockOffset(deviceSentAt: Instant, receivedAt: Instant): Duration =
        Duration.between(deviceSentAt, receivedAt)

    fun estimatedClockOffset(message: DeviceMessage): Duration =
        estimatedClockOffset(message.deviceSentAt, message.receivedAt)

    /** How long the message waited on the device (offline, retrying, batching). */
    fun deviceBufferDelay(message: DeviceMessage): Duration =
        Duration.between(message.deviceTime, message.deviceSentAt).coerceAtLeast(Duration.ZERO)

    /**
     * End-to-end latency from the moment the event happened until [processedAt], computed
     * without ever subtracting a device timestamp from a server timestamp.
     */
    fun endToEndLatency(message: DeviceMessage, processedAt: Instant): Duration =
        deviceBufferDelay(message) + Duration.between(message.receivedAt, processedAt).coerceAtLeast(Duration.ZERO)

    /**
     * Event time translated to the server clock. Only applied when the estimated offset is
     * larger than [tolerance]; below that the correction would mostly add network jitter.
     */
    fun correctedEventTime(message: DeviceMessage, tolerance: Duration): CorrectedTime {
        val offset = estimatedClockOffset(message)
        return if (offset.abs() > tolerance) {
            CorrectedTime(message.deviceTime.plus(offset), offset)
        } else {
            CorrectedTime(message.deviceTime, Duration.ZERO)
        }
    }
}

data class CorrectedTime(val eventTime: Instant, val offsetApplied: Duration)
