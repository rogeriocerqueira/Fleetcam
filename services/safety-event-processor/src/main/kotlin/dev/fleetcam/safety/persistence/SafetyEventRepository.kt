package dev.fleetcam.safety.persistence

import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.time.CorrectedTime
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class SafetyEventRepository(private val jdbc: JdbcClient) {

    /**
     * Idempotent write: returns false when the message_id was already stored
     * (device resent after a lost ack, or the consumer reprocessed after a rebalance).
     */
    fun insertIfAbsent(e: SafetyEventReceived, corrected: CorrectedTime, processedAt: Instant): Boolean =
        jdbc.sql(
            """
            insert into safety_event (message_id, device_id, firmware_version, type, confidence, latitude, longitude,
                                      speed_kmh, clip_id, device_time, device_sent_at, received_at, processed_at,
                                      event_time, clock_offset_applied_ms)
            values (:messageId, :deviceId, :firmware, :type, :confidence, :lat, :lon,
                    :speed, :clipId, :deviceTime, :sentAt, :receivedAt, :processedAt,
                    :eventTime, :offsetMs)
            on conflict (message_id) do nothing
            """.trimIndent(),
        )
            .param("messageId", e.messageId)
            .param("deviceId", e.deviceId)
            .param("firmware", e.firmwareVersion)
            .param("type", e.type)
            .param("confidence", e.confidence)
            .param("lat", e.latitude)
            .param("lon", e.longitude)
            .param("speed", e.speedKmh)
            .param("clipId", e.clipId)
            .param("deviceTime", e.deviceTime.toOdt())
            .param("sentAt", e.deviceSentAt.toOdt())
            .param("receivedAt", e.receivedAt.toOdt())
            .param("processedAt", processedAt.toOdt())
            .param("eventTime", corrected.eventTime.toOdt())
            .param("offsetMs", corrected.offsetApplied.toMillis())
            .update() == 1

    fun count(): Long = jdbc.sql("select count(*) from safety_event").query(Long::class.javaObjectType).single()

    private fun Instant.toOdt(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
