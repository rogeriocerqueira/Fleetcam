package dev.fleetcam.contracts.events

import java.time.Instant

/**
 * Kafka topics. Every topic is keyed by `deviceId`, so all messages from one device
 * land on the same partition and are consumed in order (see docs/adr/0001).
 */
object Topics {
    const val HEARTBEATS = "device.heartbeats.v1"
    const val SAFETY_EVENTS = "device.safety-events.v1"
    const val SAFETY_EVENTS_DLT = "device.safety-events.v1.DLT"
}

/**
 * Every event carries three timestamps on purpose:
 *  - [deviceTime]: device clock when the message was produced (may be wrong!)
 *  - [deviceSentAt]: device clock when the batch left the device
 *  - [receivedAt]: server clock when the gateway received the batch (trusted)
 *
 * Differences between timestamps from the *same* clock are reliable; differences
 * across clocks are not. See [dev.fleetcam.contracts.time.ClockMath].
 */
interface DeviceMessage {
    val messageId: String
    val deviceId: String
    val firmwareVersion: String
    val deviceTime: Instant
    val deviceSentAt: Instant
    val receivedAt: Instant
}

data class HeartbeatReceived(
    override val messageId: String,
    override val deviceId: String,
    override val firmwareVersion: String,
    override val deviceTime: Instant,
    override val deviceSentAt: Instant,
    override val receivedAt: Instant,
    val uptimeSeconds: Long,
    val sdCard: SdCardSnapshot,
    val network: String,
    val signalDbm: Int,
    val pendingUploads: Int,
) : DeviceMessage

data class SdCardSnapshot(
    val totalBytes: Long,
    val freeBytes: Long,
    val writeErrorsSinceBoot: Int,
    val readOnly: Boolean,
)

data class SafetyEventReceived(
    override val messageId: String,
    override val deviceId: String,
    override val firmwareVersion: String,
    override val deviceTime: Instant,
    override val deviceSentAt: Instant,
    override val receivedAt: Instant,
    val type: String,
    val confidence: Double,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val clipId: String?,
) : DeviceMessage
