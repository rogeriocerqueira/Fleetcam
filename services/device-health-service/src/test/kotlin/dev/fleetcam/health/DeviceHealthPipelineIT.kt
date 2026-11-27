package dev.fleetcam.health

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.events.SdCardSnapshot
import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.health.service.OfflineSweeper
import org.apache.kafka.clients.admin.NewTopic
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/**
 * End-to-end through real Kafka and Postgres (Testcontainers): heartbeats become device state,
 * facts are dated when the gateway received them, and the sweeper marks silent devices OFFLINE.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.properties.spring.json.add.type.headers=false",
        // Tests drive the sweep explicitly, so the scheduler must not race them.
        "fleetcam.health.offline-sweep-interval-ms=3600000",
    ],
)
@Testcontainers
class DeviceHealthPipelineIT {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @Container
        @ServiceConnection
        @JvmStatic
        val kafka = KafkaContainer("apache/kafka:3.8.0")
    }

    /** In production the ingestion-service creates this topic. Here nothing else does, so the test declares it. */
    @TestConfiguration
    class HeartbeatTopic {
        @Bean
        fun heartbeatsTopic(): NewTopic = TopicBuilder.name(Topics.HEARTBEATS).partitions(1).replicas(1).build()
    }

    @Autowired lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sweeper: OfflineSweeper

    /** Truncated to microseconds, the precision Postgres stores, so equality assertions are exact. */
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun heartbeat(deviceId: String, deviceTime: Instant, receivedAt: Instant, readOnly: Boolean = false) = HeartbeatReceived(
        messageId = UUID.randomUUID().toString(), deviceId = deviceId, firmwareVersion = "2.3.1",
        deviceTime = deviceTime, deviceSentAt = deviceTime, receivedAt = receivedAt,
        uptimeSeconds = 3600,
        sdCard = SdCardSnapshot(totalBytes = 128_000_000_000, freeBytes = 60_000_000_000, writeErrorsSinceBoot = 0, readOnly = readOnly),
        network = "LTE", signalDbm = -80, pendingUploads = 0,
    )

    private fun send(hb: HeartbeatReceived) {
        kafkaTemplate.send(Topics.HEARTBEATS, hb.deviceId, hb)
        kafkaTemplate.flush()
    }

    private fun stateOf(deviceId: String): String? =
        jdbc.sql("select state from device where device_id = :id")
            .param("id", deviceId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun lastSeenOf(deviceId: String): Instant =
        jdbc.sql("select last_seen_at from device where device_id = :id")
            .param("id", deviceId)
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()

    @Test
    fun `read-only SD card degrades the camera and the transition is dated when the gateway received it`() {
        val deviceId = "it-sd-${UUID.randomUUID()}"
        val received = now.minus(Duration.ofMinutes(15)) // consumer processes it 15 min later
        send(heartbeat(deviceId, deviceTime = received, receivedAt = received, readOnly = true))

        await().atMost(Duration.ofSeconds(60)).untilAsserted { assertEquals("DEGRADED", stateOf(deviceId)) }

        val transitionAt = jdbc.sql("select at from device_state_transition where device_id = :id")
            .param("id", deviceId)
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()
        assertEquals(received, transitionAt)
    }

    @Test
    fun `a camera silent past offlineAfter goes OFFLINE, judged against the newest heartbeat processed`() {
        val silent = "it-silent-${UUID.randomUUID()}"
        val live = "it-live-${UUID.randomUUID()}"
        send(heartbeat(silent, deviceTime = now.minus(Duration.ofMinutes(10)), receivedAt = now.minus(Duration.ofMinutes(10))))
        send(heartbeat(live, deviceTime = now, receivedAt = now))

        await().atMost(Duration.ofSeconds(60)).untilAsserted {
            assertEquals("ACTIVE", stateOf(silent))
            assertEquals("ACTIVE", stateOf(live))
        }

        sweeper.sweep()

        assertEquals("OFFLINE", stateOf(silent))
        assertEquals("ACTIVE", stateOf(live))
        val openOffline = jdbc.sql("select count(*) from device_health_issue where device_id = :id and type = 'OFFLINE' and resolved_at is null")
            .param("id", silent)
            .query(Long::class.java)
            .single()
        assertEquals(1L, openOffline)
    }

    @Test
    fun `a replayed older heartbeat refreshes lastSeen but never undoes a newer DEGRADED state`() {
        val deviceId = "it-replay-${UUID.randomUUID()}"
        send(heartbeat(deviceId, deviceTime = now, receivedAt = now, readOnly = true))
        await().atMost(Duration.ofSeconds(60)).untilAsserted { assertEquals("DEGRADED", stateOf(deviceId)) }

        // Duplicate of an older, healthy heartbeat (lost ack): its device time is behind, its receive time is later.
        val replayReceivedAt = now.plusSeconds(60)
        send(heartbeat(deviceId, deviceTime = now.minus(Duration.ofHours(1)), receivedAt = replayReceivedAt))

        await().atMost(Duration.ofSeconds(60)).untilAsserted { assertEquals(replayReceivedAt, lastSeenOf(deviceId)) }
        assertEquals("DEGRADED", stateOf(deviceId))
    }
}
