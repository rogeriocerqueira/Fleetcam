package dev.fleetcam.health.domain

import java.time.Duration
import java.time.Instant

/**
 * Device lifecycle as seen by the backend.
 *
 *   (first heartbeat) ──► ACTIVE ◄──────► DEGRADED
 *                           │  ▲              │
 *          no heartbeat     ▼  │ heartbeat    ▼
 *          for offlineAfter OFFLINE ◄─────────┘
 */
enum class DeviceState { ACTIVE, DEGRADED, OFFLINE }

enum class Severity { WARNING, CRITICAL }

enum class IssueType(val severity: Severity) {
    SD_CARD_READ_ONLY(Severity.CRITICAL),
    SD_CARD_WRITE_ERRORS(Severity.WARNING),
    SD_CARD_LOW_SPACE(Severity.WARNING),
    REBOOT_LOOP(Severity.CRITICAL),
    CLOCK_SKEW(Severity.WARNING),
    OFFLINE(Severity.CRITICAL),
}

/** `detail` is written for Support/CS: plain Portuguese, says what the customer experiences. */
data class DetectedIssue(val type: IssueType, val detail: String)

data class HealthThresholds(
    val sdWriteErrorsWarn: Int = 10,
    val sdLowSpaceRatio: Double = 0.05,
    val rebootLoopCount: Int = 3,
    val rebootLoopWindow: Duration = Duration.ofMinutes(30),
    val clockSkewTolerance: Duration = Duration.ofMinutes(5),
    val offlineAfter: Duration = Duration.ofMinutes(5),
)

data class DeviceSnapshot(
    val deviceId: String,
    val firmwareVersion: String,
    val state: DeviceState,
    /** Server clock. The only timestamp used for offline detection. */
    val lastSeenAt: Instant,
    /** Device clock. Used to detect out-of-order messages and reboot windows. */
    val lastDeviceTime: Instant,
    val lastUptimeSeconds: Long,
    val sdTotalBytes: Long,
    val sdFreeBytes: Long,
    val sdReadOnly: Boolean,
    val sdWriteErrors: Int,
    val network: String,
    val signalDbm: Int,
    val rebootWindowStartedAt: Instant?,
    val rebootsInWindow: Int,
    val openIssues: Set<IssueType>,
)

data class StateTransition(val from: DeviceState?, val to: DeviceState, val reason: String)

data class Evaluation(
    val snapshot: DeviceSnapshot,
    val opened: List<DetectedIssue>,
    val resolved: Set<IssueType>,
    val transition: StateTransition?,
)
