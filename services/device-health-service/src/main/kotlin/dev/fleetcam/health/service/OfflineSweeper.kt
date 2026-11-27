package dev.fleetcam.health.service

import dev.fleetcam.health.HealthProperties
import dev.fleetcam.health.persistence.DeviceRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Absence of data can't be detected by a Kafka consumer — nothing arrives. A periodic
 * sweep finds devices that have been silent for longer than `offlineAfter`.
 *
 * Silence is judged against the WATERMARK (newest heartbeat already processed), not the wall clock.
 * If this service's consumer lags or was down, heartbeats are still waiting in Kafka: judging by the
 * wall clock would mark the whole fleet OFFLINE in the middle of an incident that is ours, not the
 * devices'. Trade-off: if every device goes quiet (tiny fleet, all vehicles parked) nothing is marked
 * offline until one reports again; a stopped gateway is caught by the ingestion-rate alert instead.
 *
 * Running several instances is safe (row lock + idempotent evaluation), just wasteful;
 * at scale this would use ShedLock or a dedicated scheduler.
 */
@Component
class OfflineSweeper(
    private val repo: DeviceRepository,
    private val service: DeviceHealthService,
    private val props: HealthProperties,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${fleetcam.health.offline-sweep-interval-ms:30000}")
    fun sweep() {
        val watermark = repo.latestSeenAt() ?: return // no devices yet
        val now = clock.instant()
        val reference = minOf(now, watermark)
        val lag = Duration.between(watermark, now)
        if (lag > props.offlineAfter) {
            log.warn("event=offline_sweep_behind lag_s={} msg=\"judging silence by the watermark, not the wall clock\"", lag.seconds)
        }

        val sample = Timer.start(registry)
        val candidates = repo.findOfflineCandidates(reference.minus(props.offlineAfter), BATCH_SIZE)
        var marked = 0
        var failed = 0
        for (deviceId in candidates) {
            try {
                if (service.markOfflineIfSilent(deviceId, reference)) marked++
            } catch (e: Exception) {
                // One failing device must not leave the rest of the fleet unchecked.
                failed++
                log.error("event=offline_check_failed device_id={}", deviceId, e)
            }
        }
        sample.stop(registry.timer("fleetcam.health.offline.sweep"))
        registry.counter("fleetcam.health.offline.marked").increment(marked.toDouble())
        if (failed > 0) registry.counter("fleetcam.health.offline.failed").increment(failed.toDouble())
        if (candidates.isNotEmpty()) {
            log.info(
                "event=offline_sweep candidates={} marked={} failed={} batch_full={}",
                candidates.size, marked, failed, candidates.size == BATCH_SIZE,
            )
        }
    }

    companion object {
        /** Bounds one sweep after an outage; the rest is picked up on the next runs, oldest first. */
        const val BATCH_SIZE = 500
    }
}
