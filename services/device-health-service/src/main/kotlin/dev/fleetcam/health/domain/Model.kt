package dev.fleetcam.health.domain

import java.time.Duration
import java.time.Instant

/**
 * Device lifecycle as seen by the backend. The state is DERIVED from the open issues ([from]):
 *
 *   OFFLINE   the OFFLINE issue is open (no heartbeat for offlineAfter)
 *   DEGRADED  at least one CRITICAL issue is open: the camera may not be recording
 *   ACTIVE    otherwise. WARNING issues stay visible in openIssues but do not degrade (ADR 0005)
 *
 * Any state can move to any other: the first heartbeat may already carry a critical issue, and
 * the heartbeat that resolves OFFLINE decides between ACTIVE and DEGRADED with what it reports.
 */
enum class DeviceState {
    ACTIVE, DEGRADED, OFFLINE;

    companion object {
        /** Single source of truth for the state: it can never contradict the open issues. */
        fun from(openIssues: Set<IssueType>): DeviceState = when {
            IssueType.OFFLINE in openIssues -> OFFLINE
            openIssues.any { it.severity == Severity.CRITICAL } -> DEGRADED
            else -> ACTIVE
        }
    }
}

enum class Severity { WARNING, CRITICAL }

enum class IssueType(val severity: Severity) {
    SD_CARD_READ_ONLY(Severity.CRITICAL),
    SD_CARD_WRITE_ERRORS(Severity.WARNING),
    SD_CARD_LOW_SPACE(Severity.WARNING),
    REBOOT_LOOP(Severity.CRITICAL),
    CLOCK_SKEW(Severity.WARNING),
    OFFLINE(Severity.CRITICAL),
}

/**
 * The facts behind an issue, typed. The domain never builds text: wording for Support lives in
 * `presentation/IssueText.kt`, so a rule can change without touching any sentence, and vice versa.
 */
sealed interface IssueDetail {
    data object SdReadOnly : IssueDetail
    data class SdWriteErrors(val count: Int) : IssueDetail
    /** [freePercent] is 0..100. */
    data class SdLowSpace(val freePercent: Double) : IssueDetail
    data class RebootLoop(val reboots: Int, val window: Duration) : IssueDetail
    /** [deviceBehind] = false means the device clock is ahead of the server clock. */
    data class ClockSkew(val deviceBehind: Boolean, val offset: Duration, val firmware: String) : IssueDetail
    data class Offline(val silence: Duration, val lastSeenAt: Instant) : IssueDetail
}

data class DetectedIssue(val type: IssueType, val detail: IssueDetail)

/** Typed device identifier: the domain can't mix it up with other strings (messageId, firmware, ...). */
@JvmInline
value class DeviceId(val value: String) {
    override fun toString() = value
}

/**
 * No default values on purpose: every place that builds thresholds must pass all of them, so a new
 * threshold forgotten in HealthProperties.toThresholds() is a compile error, not a silently ignored setting.
 */
data class HealthThresholds(
    val sdWriteErrorsWarn: Int,
    val sdLowSpaceRatio: Double,
    val rebootLoopCount: Int,
    val rebootLoopWindow: Duration,
    val clockSkewTolerance: Duration,
    val offlineAfter: Duration,
) {
    init {
        // Domain invariants. A typo in the config (e.g. 5 instead of 0.05) would otherwise raise
        // alerts for the whole fleet; refusing to start is the safer failure.
        require(sdWriteErrorsWarn > 0) { "sdWriteErrorsWarn must be > 0, got $sdWriteErrorsWarn" }
        require(sdLowSpaceRatio > 0.0 && sdLowSpaceRatio < 1.0) {
            "sdLowSpaceRatio is a fraction (0.05 = 5%), got $sdLowSpaceRatio"
        }
        require(rebootLoopCount >= 2) { "rebootLoopCount must be >= 2, got $rebootLoopCount" }
        require(rebootLoopWindow > Duration.ZERO) { "rebootLoopWindow must be positive, got $rebootLoopWindow" }
        require(clockSkewTolerance >= Duration.ofSeconds(30)) {
            "clockSkewTolerance below 30s would flag network jitter as clock skew, got $clockSkewTolerance"
        }
        require(offlineAfter >= Duration.ofMinutes(1)) {
            "offlineAfter below 1 min would flap on every missed heartbeat, got $offlineAfter"
        }
    }

    companion object {
        val DEFAULTS = HealthThresholds(
            sdWriteErrorsWarn = 10,
            sdLowSpaceRatio = 0.05,
            rebootLoopCount = 3,
            rebootLoopWindow = Duration.ofMinutes(30),
            clockSkewTolerance = Duration.ofMinutes(5),
            offlineAfter = Duration.ofMinutes(5),
        )
    }
}

data class DeviceSnapshot(
    val deviceId: DeviceId,
    val firmwareVersion: String,
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
) {
    /** Derived, never set independently. Persisted only so it can be queried and indexed. */
    val state: DeviceState get() = DeviceState.from(openIssues)
}

data class StateTransition(val from: DeviceState?, val to: DeviceState, val reason: String)

data class Evaluation(
    val snapshot: DeviceSnapshot,
    val opened: List<DetectedIssue>,
    val resolved: Set<IssueType>,
    val transition: StateTransition?,
)
