package dev.fleetcam.ingestion.grpc

import com.google.protobuf.Timestamp
import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.events.SdCardSnapshot
import dev.fleetcam.contracts.telemetry.v1.SafetyEventType
import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import dev.fleetcam.contracts.telemetry.v1.TelemetryEnvelope
import java.time.Instant

data class Rejection(val messageId: String, val reason: String)

data class MappingResult(
    val heartbeats: List<HeartbeatReceived>,
    val safetyEvents: List<SafetyEventReceived>,
    val rejected: List<Rejection>,
) {
    val accepted: Int get() = heartbeats.size + safetyEvents.size
}

/**
 * Translates the wire format (protobuf) into the internal event contracts (Kafka/JSON).
 *
 * Invalid items are rejected individually instead of failing the whole batch: one corrupt
 * message from a buggy firmware must not block every other message the device buffered.
 */
object TelemetryMapper {

    fun map(batch: TelemetryBatch, receivedAt: Instant): MappingResult {
        val sentAt = batch.deviceSentAt.toInstant()
        val firmware = batch.firmwareVersion.ifBlank { "unknown" }
        val heartbeats = mutableListOf<HeartbeatReceived>()
        val safetyEvents = mutableListOf<SafetyEventReceived>()
        val rejected = mutableListOf<Rejection>()

        for (item in batch.itemsList) {
            val reason = validate(item)
            if (reason != null) {
                rejected += Rejection(item.messageId.ifBlank { "<missing>" }, reason)
                continue
            }
            when (item.payloadCase) {
                TelemetryEnvelope.PayloadCase.HEARTBEAT -> heartbeats += item.toHeartbeat(batch.deviceId, firmware, sentAt, receivedAt)
                TelemetryEnvelope.PayloadCase.SAFETY_EVENT -> safetyEvents += item.toSafetyEvent(batch.deviceId, firmware, sentAt, receivedAt)
                else -> rejected += Rejection(item.messageId, "payload not set")
            }
        }
        return MappingResult(heartbeats, safetyEvents, rejected)
    }

    private fun validate(item: TelemetryEnvelope): String? = when {
        item.messageId.isBlank() -> "message_id is required"
        !item.hasDeviceTime() -> "device_time is required"
        item.payloadCase == TelemetryEnvelope.PayloadCase.PAYLOAD_NOT_SET -> "payload not set"
        item.payloadCase == TelemetryEnvelope.PayloadCase.SAFETY_EVENT &&
            item.safetyEvent.type in setOf(SafetyEventType.SAFETY_EVENT_TYPE_UNSPECIFIED, SafetyEventType.UNRECOGNIZED) ->
            "safety_event.type is required"
        else -> null
    }

    private fun TelemetryEnvelope.toHeartbeat(deviceId: String, firmware: String, sentAt: Instant, receivedAt: Instant) =
        HeartbeatReceived(
            messageId = messageId,
            deviceId = deviceId,
            firmwareVersion = firmware,
            deviceTime = deviceTime.toInstant(),
            deviceSentAt = sentAt,
            receivedAt = receivedAt,
            uptimeSeconds = heartbeat.uptimeSeconds,
            sdCard = SdCardSnapshot(
                totalBytes = heartbeat.sdCard.totalBytes,
                freeBytes = heartbeat.sdCard.freeBytes,
                writeErrorsSinceBoot = heartbeat.sdCard.writeErrorsSinceBoot,
                readOnly = heartbeat.sdCard.readOnly,
            ),
            network = heartbeat.connectivity.network.name.removePrefix("NETWORK_TYPE_"),
            signalDbm = heartbeat.connectivity.signalDbm,
            pendingUploads = heartbeat.pendingUploads,
        )

    private fun TelemetryEnvelope.toSafetyEvent(deviceId: String, firmware: String, sentAt: Instant, receivedAt: Instant) =
        SafetyEventReceived(
            messageId = messageId,
            deviceId = deviceId,
            firmwareVersion = firmware,
            deviceTime = deviceTime.toInstant(),
            deviceSentAt = sentAt,
            receivedAt = receivedAt,
            type = safetyEvent.type.name.removePrefix("SAFETY_EVENT_TYPE_"),
            confidence = safetyEvent.confidence.toDouble(),
            latitude = safetyEvent.latitude,
            longitude = safetyEvent.longitude,
            speedKmh = safetyEvent.speedKmh.toDouble(),
            clipId = safetyEvent.clipId.ifBlank { null },
        )

    private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
}
