package dev.fleetcam.health.service

import dev.fleetcam.health.HealthProperties
import dev.fleetcam.health.persistence.DeviceRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineSweeperTest {

    private val now = Instant.parse("2026-11-24T15:00:00Z")
    private val offlineAfter = Duration.ofMinutes(5)

    private val repo = mock(DeviceRepository::class.java)
    private val service = mock(DeviceHealthService::class.java)
    private val registry = SimpleMeterRegistry()
    private val sweeper = OfflineSweeper(
        repo = repo,
        service = service,
        props = HealthProperties(offlineAfter = offlineAfter),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        registry = registry,
    )

    private fun marked() = registry.counter("fleetcam.health.offline.marked").count()

    @Test
    fun `consumer 10 min behind does not mark the fleet offline`() {
        // Newest processed heartbeat is 10 min old: heartbeats are waiting in Kafka, devices are fine.
        val watermark = now.minus(Duration.ofMinutes(10))
        `when`(repo.latestSeenAt()).thenReturn(watermark)
        `when`(repo.findOfflineCandidates(watermark.minus(offlineAfter), OfflineSweeper.BATCH_SIZE)).thenReturn(emptyList())

        sweeper.sweep()

        // Judged against the watermark, not against now - 5 min (which would select almost everyone).
        verify(repo).findOfflineCandidates(watermark.minus(offlineAfter), OfflineSweeper.BATCH_SIZE)
        verify(repo, never()).findOfflineCandidates(now.minus(offlineAfter), OfflineSweeper.BATCH_SIZE)
        assertEquals(0.0, marked())
    }

    @Test
    fun `one failing device does not stop the sweep`() {
        `when`(repo.latestSeenAt()).thenReturn(now)
        `when`(repo.findOfflineCandidates(now.minus(offlineAfter), OfflineSweeper.BATCH_SIZE))
            .thenReturn(listOf("cam-1", "cam-2", "cam-3"))
        `when`(service.markOfflineIfSilent("cam-1", now)).thenReturn(true)
        `when`(service.markOfflineIfSilent("cam-2", now)).thenThrow(RuntimeException("db timeout"))
        `when`(service.markOfflineIfSilent("cam-3", now)).thenReturn(true)

        sweeper.sweep()

        verify(service).markOfflineIfSilent("cam-3", now)
        assertEquals(2.0, marked())
        assertEquals(1.0, registry.counter("fleetcam.health.offline.failed").count())
    }

    @Test
    fun `candidate that came back before the lock is not counted as marked`() {
        `when`(repo.latestSeenAt()).thenReturn(now)
        `when`(repo.findOfflineCandidates(now.minus(offlineAfter), OfflineSweeper.BATCH_SIZE)).thenReturn(listOf("cam-1"))
        `when`(service.markOfflineIfSilent("cam-1", now)).thenReturn(false)

        sweeper.sweep()

        assertEquals(0.0, marked())
    }

    @Test
    fun `no devices yet means nothing to sweep`() {
        `when`(repo.latestSeenAt()).thenReturn(null)

        sweeper.sweep()

        verifyNoInteractions(service)
    }
}
