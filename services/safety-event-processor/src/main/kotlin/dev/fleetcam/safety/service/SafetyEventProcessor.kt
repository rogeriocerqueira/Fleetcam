package dev.fleetcam.safety.service

import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.time.ClockMath
import dev.fleetcam.safety.SafetyProperties
import dev.fleetcam.safety.domain.SafetyEventValidator
import dev.fleetcam.safety.metrics.SafetyMetrics
import dev.fleetcam.safety.persistence.SafetyEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

@Service
class SafetyEventProcessor(
    private val repo: SafetyEventRepository,
    private val metrics: SafetyMetrics,
    private val props: SafetyProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(event: SafetyEventReceived) {
        SafetyEventValidator.requireValid(event) // throws -> DLT, no retries

        val processedAt = clock.instant()
        val corrected = ClockMath.correctedEventTime(event, props.clockCorrectionTolerance)
        val inserted = repo.insertIfAbsent(event, corrected, processedAt)

        if (!inserted) {
            metrics.outcome("duplicate", event)
            return
        }
        metrics.outcome("stored", event)
        metrics.endToEndLatency(event, ClockMath.endToEndLatency(event, processedAt))
        if (corrected.offsetApplied != Duration.ZERO) {
            metrics.clockCorrected(event)
            log.info(
                "event=clock_corrected device_id={} firmware={} offset_min={} message_id={}",
                event.deviceId, event.firmwareVersion, corrected.offsetApplied.toMinutes(), event.messageId,
            )
        }
    }
}
