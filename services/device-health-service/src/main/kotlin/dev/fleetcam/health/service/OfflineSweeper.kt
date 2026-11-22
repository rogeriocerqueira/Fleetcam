package dev.fleetcam.health.service

import dev.fleetcam.health.HealthProperties
import dev.fleetcam.health.persistence.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Absence of data can't be detected by a Kafka consumer — nothing arrives. A periodic
 * sweep finds devices whose last heartbeat (server clock) is older than `offlineAfter`.
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${fleetcam.health.offline-sweep-interval-ms:30000}")
    fun sweep() {
        val now = clock.instant()
        val candidates = repo.findOfflineCandidates(now.minus(props.offlineAfter))
        candidates.forEach { service.markOfflineIfSilent(it, now) }
        if (candidates.isNotEmpty()) log.info("event=offline_sweep candidates={}", candidates.size)
    }
}
