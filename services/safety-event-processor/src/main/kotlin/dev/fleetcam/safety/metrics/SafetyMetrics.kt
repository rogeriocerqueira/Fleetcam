package dev.fleetcam.safety.metrics

import dev.fleetcam.contracts.events.SafetyEventReceived
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * SLO metric: `fleetcam.safety.e2e.latency` — time from the event happening on the road to
 * being stored and visible in the panel. The 30s SLO bucket is published explicitly so the
 * Prometheus rule can compute good/total without interpolating percentiles.
 */
@Component
class SafetyMetrics(private val registry: MeterRegistry) {

    fun outcome(outcome: String, e: SafetyEventReceived? = null) {
        registry.counter("fleetcam.safety.events", "outcome", outcome, "type", e?.type ?: "unknown").increment()
    }

    fun endToEndLatency(e: SafetyEventReceived, latency: Duration) {
        Timer.builder("fleetcam.safety.e2e.latency")
            .description("Event happened -> stored, computed without crossing device/server clocks")
            .tag("type", e.type)
            .serviceLevelObjectives(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMinutes(10))
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMillis(50))
            .maximumExpectedValue(Duration.ofHours(6))
            .register(registry)
            .record(latency)
    }

    fun clockCorrected(e: SafetyEventReceived) {
        registry.counter("fleetcam.safety.clock.corrected", "firmware", e.firmwareVersion).increment()
    }
}
