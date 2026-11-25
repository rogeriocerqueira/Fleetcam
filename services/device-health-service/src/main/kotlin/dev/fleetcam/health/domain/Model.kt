/**
 * Device lifecycle as seen by the backend. The state is DERIVED from the open issues:
 *
 *   first heartbeat ──► ACTIVE ◄─────────► DEGRADED   (only CRITICAL issues degrade;
 *        │                 ▲   \         ▲  │          warnings stay visible in openIssues)
 *        └─────────────────┼────\───────►┘  │
 *                 heartbeat│     ▼          ▼
 *                          └──── OFFLINE ◄──┘  no heartbeat for offlineAfter
 */
enum class DeviceState {
    ACTIVE, DEGRADED, OFFLINE;

    companion object {
        /** Single source of truth: every snapshot's state comes from here. */
        fun from(openIssues: Set<IssueType>): DeviceState = when {
            IssueType.OFFLINE in openIssues -> OFFLINE
            openIssues.any { it.severity == Severity.CRITICAL } -> DEGRADED
            else -> ACTIVE
        }
    }
}

data class HealthThresholds(
    val sdWriteErrorsWarn: Int,
    val sdLowSpaceRatio: Double,
    val rebootLoopCount: Int,
    val rebootLoopWindow: Duration,
    val clockSkewTolerance: Duration,
    val offlineAfter: Duration,
) {
    init {
        // Domain invariants: a typo in config would otherwise raise alerts for the whole fleet.
        require(sdWriteErrorsWarn > 0) { "sdWriteErrorsWarn must be > 0, got $sdWriteErrorsWarn" }
        require(sdLowSpaceRatio > 0.0 && sdLowSpaceRatio < 1.0) { "sdLowSpaceRatio is a fraction (0.05 = 5%), got $sdLowSpaceRatio" }
        require(rebootLoopCount >= 2) { "rebootLoopCount must be >= 2, got $rebootLoopCount" }
        require(rebootLoopWindow > Duration.ZERO) { "rebootLoopWindow must be positive" }
        require(clockSkewTolerance >= Duration.ofSeconds(30)) { "clockSkewTolerance below 30s would flag network jitter" }
        require(offlineAfter >= Duration.ofMinutes(1)) { "offlineAfter below 1 min would flap on every missed heartbeat" }
    }

    companion object {
        val DEFAULTS = HealthThresholds(10, 0.05, 3, Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(5))
    }
}

data class DeviceSnapshot(
    /* ...same fields... */
    val openIssues: Set<IssueType>,
) {
    /** Derived, never stored independently: it cannot contradict openIssues. */
    val state: DeviceState get() = DeviceState.from(openIssues)
}