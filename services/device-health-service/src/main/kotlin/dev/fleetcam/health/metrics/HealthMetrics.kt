package dev.fleetcam.health.metrics

import dev.fleetcam.health.domain.DeviceState
import dev.fleetcam.health.domain.IssueType
import dev.fleetcam.health.persistence.DeviceRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Fleet-level gauges come from the database (source of truth), refreshed periodically,
 * instead of being incremented in memory — they stay correct across restarts and replicas.
 */
@Component
class HealthMetrics(
    private val registry: MeterRegistry,
    private val repo: DeviceRepository,
) {
    private val devicesByState = MultiGauge.builder("fleetcam.devices")
        .description("Devices per lifecycle state")
        .register(registry)

    private val openIssuesByType = MultiGauge.builder("fleetcam.device.issues.open")
        .description("Open health issues per type")
        .register(registry)

    @Scheduled(fixedDelayString = "\${fleetcam.health.gauge-refresh-ms:15000}")
    fun refreshGauges() {
        val byState = repo.countByState()
        devicesByState.register(DeviceState.entries.map { MultiGauge.Row.of(Tags.of("state", it.name), byState[it] ?: 0L) }, true)

        val byIssue = repo.countOpenIssuesByType()
        openIssuesByType.register(
            IssueType.entries.map { MultiGauge.Row.of(Tags.of("type", it.name, "severity", it.severity.name), byIssue[it] ?: 0L) },
            true,
        )
    }

    fun issueOpened(type: IssueType) = registry.counter("fleetcam.device.issues.opened", "type", type.name).increment()

    fun issueResolved(type: IssueType) = registry.counter("fleetcam.device.issues.resolved", "type", type.name).increment()
}
