package dev.fleetcam.ingestion.grpc

import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.contracts.telemetry.v1.DeviceGatewayGrpcKt
import dev.fleetcam.contracts.telemetry.v1.PublishResponse
import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import dev.fleetcam.ingestion.IngestionProperties
import dev.fleetcam.ingestion.metrics.IngestionMetrics
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Receives telemetry batches from devices and publishes them to Kafka.
 *
 * The response is only sent after Kafka acknowledged every message (acks=all). That is the
 * contract that allows the device to delete its local buffer: if we answered before the ack,
 * a broker failure would silently lose data. If Kafka is unavailable we return UNAVAILABLE
 * and the device retries the whole batch later (duplicates are handled downstream).
 */
@Component
class DeviceGatewayService(
    private val kafka: KafkaTemplate<String, Any>,
    private val props: IngestionProperties,
    private val metrics: IngestionMetrics,
    private val clock: Clock,
) : DeviceGatewayGrpcKt.DeviceGatewayCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun publish(request: TelemetryBatch): PublishResponse {
        validateBatch(request)
        val receivedAt = clock.instant()
        val result = TelemetryMapper.map(request, receivedAt)

        metrics.recordBatch(request, result, receivedAt)
        if (result.rejected.isNotEmpty()) {
            log.warn(
                "Rejected {} message(s) from device={} firmware={}: {}",
                result.rejected.size, request.deviceId, request.firmwareVersion, result.rejected.take(5),
            )
        }

        try {
            val sends = result.heartbeats.map { kafka.send(Topics.HEARTBEATS, it.deviceId, it) } +
                result.safetyEvents.map { kafka.send(Topics.SAFETY_EVENTS, it.deviceId, it) }
            sends.forEach { it.await() }
        } catch (e: Exception) {
            metrics.publishFailed(result.accepted)
            log.error("Failed to publish batch from device={} to Kafka", request.deviceId, e)
            throw StatusException(Status.UNAVAILABLE.withDescription("temporarily unable to persist telemetry, retry later").withCause(e))
        }

        return PublishResponse.newBuilder()
            .setAccepted(result.accepted)
            .setRejected(result.rejected.size)
            .addAllRejectedMessageIds(result.rejected.map { it.messageId })
            .build()
    }

    private fun validateBatch(request: TelemetryBatch) {
        val problem = when {
            request.deviceId.isBlank() -> "device_id is required"
            !request.hasDeviceSentAt() -> "device_sent_at is required"
            request.itemsCount > props.maxBatchSize -> "batch too large: ${request.itemsCount} > ${props.maxBatchSize}"
            else -> null
        }
        if (problem != null) {
            metrics.batchRejected(problem)
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(problem))
        }
    }
}
