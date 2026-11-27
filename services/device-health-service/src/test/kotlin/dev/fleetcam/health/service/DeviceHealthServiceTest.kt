package dev.fleetcam.health.service

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.events.SdCardSnapshot
import dev.fleetcam.health.domain.DetectedIssue
import dev.fleetcam.health.domain.DeviceHealthEvaluator
import dev.fleetcam.health.domain.DeviceSnapshot
import dev.fleetcam.health.domain.HealthThresholds
import dev.fleetcam.health.domain.IssueType
import dev.fleetcam.health.domain.StateTransition
import dev.fleetcam.health.metrics.HealthMetrics
import dev.fleetcam.health.persistence.DeviceRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceHealthServiceTest {

    private val receivedAt = Instant.parse("2026-11-24T10:00:00Z")
    private val processedAt = receivedAt.plus(Duration.ofMinutes(15)) // consumer 15 min behind

    private val repo = mock<DeviceRepository>()
    private val registry = SimpleMeterRegistry()
    private val service = DeviceHealthService(
        repo = repo,
        evaluator = DeviceHealthEvaluator(HealthThresholds.DEFAULTS),
        metrics = HealthMetrics(registry, repo),
        clock = Clock.fixed(processedAt, ZoneOffset.UTC),
    )

    private val brokenSdHeartbeat = HeartbeatReceived(
        messageId = "hb-1", deviceId = "cam-0001", firmwareVersion = "2.2.0",
        deviceTime = receivedAt.minusSeconds(2), deviceSentAt = receivedAt.minusSeconds(1), receivedAt = receivedAt,
        uptimeSeconds = 3600,
        sdCard = SdCardSnapshot(totalBytes = 64_000_000_000, freeBytes = 20_000_000_000, writeErrorsSinceBoot = 0, readOnly = true),
        network = "LTE", signalDbm = -90, pendingUploads = 0,
    )

    @Test
    fun `issues and transitions are timestamped when received, not when processed`() {
        service.onHeartbeat(brokenSdHeartbeat) // findForUpdate returns null (mock default): first heartbeat

        val issueAt = argumentCaptor<Instant>()
        verify(repo).openIssue(any(), any<DetectedIssue>(), issueAt.capture())
        assertEquals(receivedAt, issueAt.firstValue)

        val transitionAt = argumentCaptor<Instant>()
        verify(repo).recordTransition(any(), any<StateTransition>(), transitionAt.capture())
        assertEquals(receivedAt, transitionAt.firstValue)

        // The row's updated_at still says when we processed it.
        val updatedAt = argumentCaptor<Instant>()
        verify(repo).upsert(any<DeviceSnapshot>(), updatedAt.capture())
        assertEquals(processedAt, updatedAt.firstValue)
    }

    @Test
    fun `metrics are emitted once the change is applied`() {
        service.onHeartbeat(brokenSdHeartbeat)

        assertEquals(
            1.0,
            registry.counter("fleetcam.device.issues.opened", "type", IssueType.SD_CARD_READ_ONLY.name).count(),
        )
    }
}
