package dev.fleetcam.safety

import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.events.Topics
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end through real Kafka and Postgres (Testcontainers): duplicates are absorbed,
 * invalid events go to the DLT and skewed device clocks are corrected before storage.
 */
@SpringBootTest
@Testcontainers
class SafetyEventPipelineIT {

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

    @Autowired lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var meters: MeterRegistry

    private fun event(deviceTime: Instant, sentAt: Instant, receivedAt: Instant, confidence: Double = 0.9) = SafetyEventReceived(
        messageId = UUID.randomUUID().toString(), deviceId = "cam-it-1", firmwareVersion = "2.3.1",
        deviceTime = deviceTime, deviceSentAt = sentAt, receivedAt = receivedAt,
        type = "DROWSINESS", confidence = confidence, latitude = -12.97, longitude = -38.50, speedKmh = 70.0, clipId = "clip-1",
    )

    private fun counter(outcome: String) =
        meters.find("fleetcam.safety.events").tag("outcome", outcome).counters().sumOf { it.count() }

    @Test
    fun `duplicates are stored once, invalid events are dead-lettered, skewed clocks are corrected`() {
        val now = Instant.now()
        val skew = Duration.ofHours(3)
        val skewed = event(deviceTime = now.minus(skew).minusSeconds(2), sentAt = now.minus(skew), receivedAt = now)
        val invalid = event(now, now, now, confidence = 4.2)

        kafkaTemplate.send(Topics.SAFETY_EVENTS, skewed.deviceId, skewed)
        kafkaTemplate.send(Topics.SAFETY_EVENTS, skewed.deviceId, skewed) // lost ack -> device resent
        kafkaTemplate.send(Topics.SAFETY_EVENTS, invalid.deviceId, invalid)
        kafkaTemplate.flush()

        await().atMost(Duration.ofSeconds(60)).untilAsserted {
            assertEquals(1.0, counter("stored"))
            assertEquals(1.0, counter("duplicate"))
            assertEquals(1.0, counter("dead_lettered"))
        }

        val eventTime = jdbc.sql("select event_time from safety_event where message_id = :id")
            .param("id", skewed.messageId)
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()

        // Corrected back to the server clock: ~2s before it was received, not 3 hours.
        val error = Duration.between(now.minusSeconds(2), eventTime).abs()
        assertTrue(error < Duration.ofSeconds(1), "event_time off by $error")
    }
}
