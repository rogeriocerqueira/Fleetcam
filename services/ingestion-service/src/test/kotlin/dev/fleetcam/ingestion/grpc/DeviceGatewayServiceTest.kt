package dev.fleetcam.ingestion.grpc

import com.google.protobuf.Timestamp
import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.contracts.telemetry.v1.DeviceGatewayGrpcKt
import dev.fleetcam.contracts.telemetry.v1.Heartbeat
import dev.fleetcam.contracts.telemetry.v1.SafetyEvent
import dev.fleetcam.contracts.telemetry.v1.SafetyEventType
import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import dev.fleetcam.contracts.telemetry.v1.TelemetryEnvelope
import dev.fleetcam.ingestion.IngestionProperties
import dev.fleetcam.ingestion.metrics.IngestionMetrics
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises the real gRPC stack in memory (no network, no Docker): request decoding,
 * status codes returned to the device and what is published to Kafka.
 */
class DeviceGatewayServiceTest {

    private val now = Instant.parse("2026-11-24T15:00:00Z")
    private val registry = SimpleMeterRegistry()

    @Suppress("UNCHECKED_CAST")
    private val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, Any>

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: DeviceGatewayGrpcKt.DeviceGatewayCoroutineStub

    @BeforeEach
    fun setUp() {
        val service = DeviceGatewayService(
            kafka = kafka,
            props = IngestionProperties(maxBatchSize = 3),
            metrics = IngestionMetrics(registry),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        stub = DeviceGatewayGrpcKt.DeviceGatewayCoroutineStub(channel)
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun kafkaAcks() {
        `when`(kafka.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult::class.java) as SendResult<String, Any>))
    }

    private fun ts(i: Instant) = Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

    private fun heartbeat(id: String) = TelemetryEnvelope.newBuilder()
        .setMessageId(id).setDeviceTime(ts(now.minusSeconds(5)))
        .setHeartbeat(Heartbeat.newBuilder().setUptimeSeconds(100))
        .build()

    private fun safetyEvent(id: String, type: SafetyEventType = SafetyEventType.SAFETY_EVENT_TYPE_DROWSINESS) =
        TelemetryEnvelope.newBuilder()
            .setMessageId(id).setDeviceTime(ts(now.minusSeconds(3)))
            .setSafetyEvent(SafetyEvent.newBuilder().setType(type).setConfidence(0.9f))
            .build()

    private fun batch(vararg items: TelemetryEnvelope, deviceId: String = "cam-0001") = TelemetryBatch.newBuilder()
        .setDeviceId(deviceId)
        .setFirmwareVersion("2.2.0")
        .setDeviceSentAt(ts(now.minusSeconds(1)))
        .addAllItems(items.toList())
        .build()

    @Test
    fun `publishes each message to its topic keyed by device and acks the batch`() {
        kafkaAcks()

        val response = runBlocking { stub.publish(batch(heartbeat("hb-1"), safetyEvent("ev-1"))) }

        assertEquals(2, response.accepted)
        assertEquals(0, response.rejected)
        verify(kafka).send(eq(Topics.HEARTBEATS), eq("cam-0001"), any(HeartbeatReceived::class.java))
        verify(kafka).send(eq(Topics.SAFETY_EVENTS), eq("cam-0001"), any(SafetyEventReceived::class.java))
    }

    @Test
    fun `invalid items are reported back so the device drops them instead of retrying`() {
        kafkaAcks()
        val invalid = safetyEvent("ev-bad", SafetyEventType.SAFETY_EVENT_TYPE_UNSPECIFIED)

        val response = runBlocking { stub.publish(batch(heartbeat("hb-1"), invalid)) }

        assertEquals(1, response.accepted)
        assertEquals(listOf("ev-bad"), response.rejectedMessageIdsList)
    }

    @Test
    fun `Kafka failure returns UNAVAILABLE so the device keeps its buffer and retries`() {
        `when`(kafka.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("broker down")))

        val error = assertFailsWith<StatusException> { runBlocking { stub.publish(batch(heartbeat("hb-1"))) } }

        assertEquals(Status.Code.UNAVAILABLE, error.status.code)
    }

    @Test
    fun `malformed batch is rejected with INVALID_ARGUMENT and nothing is published`() {
        val tooBig = batch(heartbeat("1"), heartbeat("2"), heartbeat("3"), heartbeat("4"))
        val noDevice = batch(heartbeat("1"), deviceId = "")

        listOf(tooBig, noDevice).forEach { b ->
            val error = assertFailsWith<StatusException> { runBlocking { stub.publish(b) } }
            assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
        }
        verify(kafka, never()).send(anyString(), anyString(), any())
    }
}
