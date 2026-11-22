package dev.fleetcam.ingestion.metrics

import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import dev.fleetcam.contracts.time.ClockMath
import dev.fleetcam.ingestion.grpc.MappingResult
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Tags are kept low-cardinality on purpose: `firmware` has a handful of values,
 * `deviceId` is NEVER a tag (tens of thousands of series would explode cost in Datadog/Prometheus).
 * Per-device questions are answered with SQL over the event store, not with metrics.
 */
@Component
class IngestionMetrics(private val registry: MeterRegistry) {

    fun recordBatch(batch: TelemetryBatch, result: MappingResult, receivedAt: Instant) {
        val firmware = batch.firmwareVersion.ifBlank { "unknown" }
        count("heartbeat", "accepted", result.heartbeats.size)
        count("safety_event", "accepted", result.safetyEvents.size)
        count("any", "rejected", result.rejected.size)

        DistributionSummary.builder("fleetcam.ingestion.batch.size")
            .description("Messages per batch; large batches indicate devices flushing an offline backlog")
            .publishPercentileHistogram()
            .maximumExpectedValue(500.0)
            .register(registry)
            .record(batch.itemsCount.toDouble())

        val offset = ClockMath.estimatedClockOffset(
            Instant.ofEpochSecond(batch.deviceSentAt.seconds, batch.deviceSentAt.nanos.toLong()),
            receivedAt,
        )
        // Micrometer histograms ignore negative values, so we record |offset| and tag the direction.
        DistributionSummary.builder("fleetcam.ingestion.clock.offset")
            .description("Estimated |device clock - server clock| per batch")
            .baseUnit("seconds")
            .tag("firmware", firmware)
            .tag("direction", if (offset.isNegative) "device_ahead" else "device_behind")
            .serviceLevelObjectives(5.0, 60.0, 300.0, 3600.0)
            .publishPercentileHistogram()
            .minimumExpectedValue(0.001)
            .maximumExpectedValue(86_400.0)
            .register(registry)
            .record(offset.abs().toMillis() / 1000.0)
    }

    fun batchRejected(reason: String) {
        registry.counter("fleetcam.ingestion.batches.rejected", "reason", reason.substringBefore(':')).increment()
    }

    fun publishFailed(messages: Int) = count("any", "publish_failed", messages)

    private fun count(type: String, outcome: String, n: Int) {
        if (n > 0) registry.counter("fleetcam.ingestion.messages", "type", type, "outcome", outcome).increment(n.toDouble())
    }
}
