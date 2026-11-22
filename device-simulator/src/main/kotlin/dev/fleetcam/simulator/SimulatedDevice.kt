package dev.fleetcam.simulator

import com.google.protobuf.Timestamp
import dev.fleetcam.contracts.telemetry.v1.Connectivity
import dev.fleetcam.contracts.telemetry.v1.Heartbeat
import dev.fleetcam.contracts.telemetry.v1.NetworkType
import dev.fleetcam.contracts.telemetry.v1.SafetyEvent
import dev.fleetcam.contracts.telemetry.v1.SafetyEventType
import dev.fleetcam.contracts.telemetry.v1.SdCardStatus
import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import dev.fleetcam.contracts.telemetry.v1.TelemetryEnvelope
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * One dashcam. Like the real thing, it keeps messages in a bounded local buffer and only
 * drops them after the gateway acknowledges the batch (at-least-once delivery).
 */
class SimulatedDevice(
    val deviceId: String,
    val faults: Set<Fault>,
    seed: Long,
    private val maxBuffer: Int = 2_000,
    private val maxBatch: Int = 200,
) {
    private val log = LoggerFactory.getLogger("device.$deviceId")
    private val random = Random(seed)

    private val firmware = if (Fault.FIRMWARE_CLOCK_SKEW in faults) "2.3.1" else "2.2.0"
    private val clockOffset: Duration = if (Fault.FIRMWARE_CLOCK_SKEW in faults) Duration.ofHours(-3) else Duration.ZERO

    private val buffer = ArrayDeque<TelemetryEnvelope>()
    private var droppedFromBuffer = 0L

    private var bootedAt = Instant.now().minusSeconds(random.nextLong(600, 36_000))
    private var ticks = 0
    private var sdWriteErrors = 0
    private var sdFree = 40_000_000_000L
    private val sdTotal = 64_000_000_000L
    private var latitude = -12.97 + random.nextDouble(-0.2, 0.2) // Salvador-BA area
    private var longitude = -38.50 + random.nextDouble(-0.2, 0.2)

    /** The device's own (possibly wrong) idea of the current time. */
    private fun deviceNow(): Instant = Instant.now().plus(clockOffset)

    fun tick(tick: Duration) {
        ticks++
        if (Fault.GOES_DARK in faults && ticks > 36) return // produces nothing anymore

        if (Fault.REBOOT_LOOP in faults && ticks % 36 == 0) { // crashes ~3 min after each boot
            bootedAt = Instant.now()
        }
        if (Fault.SD_CARD_DEGRADING in faults) {
            sdWriteErrors += random.nextInt(0, 3)
        }
        sdFree = (sdFree + random.nextLong(-50_000_000, 50_000_000)).coerceIn(1_000_000_000, sdTotal)
        latitude += random.nextDouble(-0.002, 0.002)
        longitude += random.nextDouble(-0.002, 0.002)

        enqueue(heartbeat())
        // ~1 safety event per device every 2-3 minutes at a 5s tick
        if (random.nextDouble() < tick.seconds / 150.0) enqueue(safetyEvent())
    }

    suspend fun flush(gateway: GatewayClient) {
        if (buffer.isEmpty()) return
        if (Fault.GOES_DARK in faults && ticks > 36) return

        val flaky = Fault.FLAKY_CONNECTIVITY in faults
        if (flaky && random.nextDouble() < 0.30) return // no signal this tick; keep buffering

        val items = buffer.take(maxBatch)
        val batch = TelemetryBatch.newBuilder()
            .setDeviceId(deviceId)
            .setFirmwareVersion(firmware)
            .setDeviceSentAt(deviceNow().toTimestamp())
            .addAllItems(items)
            .build()

        when (val result = gateway.publish(batch)) {
            is PublishResult.Ok -> {
                val ackLost = flaky && random.nextDouble() < 0.10
                if (ackLost) {
                    log.debug("ack lost, will resend {} messages", items.size)
                    return // server stored them, but the device doesn't know: resend next tick
                }
                repeat(items.size) { buffer.removeFirst() }
                if (result.rejected > 0) log.warn("gateway rejected {} message(s)", result.rejected)
            }
            is PublishResult.Retryable -> log.debug("gateway unavailable ({}), {} buffered", result.reason, buffer.size)
            is PublishResult.Fatal -> {
                log.error("batch rejected permanently ({}), dropping {} messages", result.reason, items.size)
                repeat(items.size) { buffer.removeFirst() }
            }
        }
    }

    private fun enqueue(message: TelemetryEnvelope) {
        if (buffer.size >= maxBuffer) {
            buffer.removeFirst()
            droppedFromBuffer++
            if (droppedFromBuffer % 100 == 1L) log.warn("local buffer full, {} oldest messages dropped so far", droppedFromBuffer)
        }
        buffer.addLast(message)
    }

    private fun heartbeat(): TelemetryEnvelope {
        val readOnly = Fault.SD_CARD_DEGRADING in faults && sdWriteErrors >= 25
        val flaky = Fault.FLAKY_CONNECTIVITY in faults
        return envelope().setHeartbeat(
            Heartbeat.newBuilder()
                .setUptimeSeconds(Duration.between(bootedAt, Instant.now()).seconds)
                .setSdCard(
                    SdCardStatus.newBuilder()
                        .setTotalBytes(sdTotal)
                        .setFreeBytes(sdFree)
                        .setWriteErrorsSinceBoot(sdWriteErrors)
                        .setReadOnly(readOnly),
                )
                .setConnectivity(
                    Connectivity.newBuilder()
                        .setNetwork(if (flaky) NetworkType.NETWORK_TYPE_3G else NetworkType.NETWORK_TYPE_LTE)
                        .setSignalDbm(if (flaky) random.nextInt(-115, -100) else random.nextInt(-95, -65)),
                )
                .setPendingUploads(if (readOnly) 0 else random.nextInt(0, 3)),
        ).build()
    }

    private fun safetyEvent(): TelemetryEnvelope {
        val types = SafetyEventType.values().filter {
            it != SafetyEventType.SAFETY_EVENT_TYPE_UNSPECIFIED && it != SafetyEventType.UNRECOGNIZED
        }
        val badGps = Fault.BAD_GPS in faults
        return envelope().setSafetyEvent(
            SafetyEvent.newBuilder()
                .setType(types[random.nextInt(types.size)])
                .setConfidence(random.nextDouble(0.55, 0.99).toFloat())
                .setLatitude(if (badGps) 0.0 else latitude)
                .setLongitude(if (badGps) 0.0 else longitude)
                .setSpeedKmh(random.nextDouble(0.0, 110.0).toFloat())
                .setClipId("clip-${UUID.randomUUID()}"),
        ).build()
    }

    private fun envelope() = TelemetryEnvelope.newBuilder()
        .setMessageId(UUID.randomUUID().toString())
        .setDeviceTime(deviceNow().toTimestamp())

    private fun Instant.toTimestamp(): Timestamp = Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()
}
