package dev.fleetcam.ingestion.grpc

import com.google.protobuf.Timestamp
import dev.fleetcam.contracts.telemetry.v1.Connectivity
import dev.fleetcam.contracts.telemetry.v1.Heartbeat
import dev.fleetcam.contracts.telemetry.v1.NetworkType
import dev.fleetcam.contracts.telemetry.v1.SafetyEvent
import dev.fleetcam.contracts.telemetry.v1.SafetyEventType
import dev.fleetcam.contracts.telemetry.v1.SdCardStatus
import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import dev.fleetcam.contracts.telemetry.v1.TelemetryEnvelope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryMapperTest {

    private val receivedAt = Instant.parse("2026-11-20T15:00:00Z")
    private fun ts(i: Instant) = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

    private val heartbeat = TelemetryEnvelope.newBuilder()
        .setMessageId("hb-1")
        .setDeviceTime(ts(receivedAt.minusSeconds(10)))
        .setHeartbeat(
            Heartbeat.newBuilder()
                .setUptimeSeconds(3600)
                .setSdCard(SdCardStatus.newBuilder().setTotalBytes(64_000_000_000).setFreeBytes(10_000_000_000).setReadOnly(true))
                .setConnectivity(Connectivity.newBuilder().setNetwork(NetworkType.NETWORK_TYPE_LTE).setSignalDbm(-95)),
        )
        .build()

    private val safetyEvent = TelemetryEnvelope.newBuilder()
        .setMessageId("ev-1")
        .setDeviceTime(ts(receivedAt.minusSeconds(5)))
        .setSafetyEvent(SafetyEvent.newBuilder().setType(SafetyEventType.SAFETY_EVENT_TYPE_PHONE_USAGE).setConfidence(0.87f))
        .build()

    private fun batch(vararg items: TelemetryEnvelope) = TelemetryBatch.newBuilder()
        .setDeviceId("cam-0001")
        .setFirmwareVersion("2.2.0")
        .setDeviceSentAt(ts(receivedAt.minusSeconds(1)))
        .addAllItems(items.toList())
        .build()

    @Test
    fun `maps heartbeats and safety events with all three timestamps`() {
        val result = TelemetryMapper.map(batch(heartbeat, safetyEvent), receivedAt)

        val hb = result.heartbeats.single()
        assertEquals("cam-0001", hb.deviceId)
        assertEquals(receivedAt, hb.receivedAt)
        assertEquals(receivedAt.minusSeconds(1), hb.deviceSentAt)
        assertEquals(true, hb.sdCard.readOnly)
        assertEquals("LTE", hb.network)

        val ev = result.safetyEvents.single()
        assertEquals("PHONE_USAGE", ev.type)
        assertEquals(0.87, ev.confidence, 0.001)
    }

    @Test
    fun `rejects only the invalid items and keeps the rest of the batch`() {
        val noId = heartbeat.toBuilder().setMessageId("").build()
        val noType = safetyEvent.toBuilder().setMessageId("ev-2")
            .setSafetyEvent(SafetyEvent.newBuilder().setConfidence(0.9f)).build()

        val result = TelemetryMapper.map(batch(heartbeat, noId, noType, safetyEvent), receivedAt)

        assertEquals(2, result.accepted)
        assertEquals(listOf("message_id is required", "safety_event.type is required"), result.rejected.map { it.reason })
    }
}
