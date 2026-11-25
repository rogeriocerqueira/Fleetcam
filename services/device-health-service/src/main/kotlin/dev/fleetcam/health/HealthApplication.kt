@ConfigurationProperties("fleetcam.health")
data class HealthProperties(
    val sdWriteErrorsWarn: Int = HealthThresholds.DEFAULTS.sdWriteErrorsWarn,
    val sdLowSpaceRatio: Double = HealthThresholds.DEFAULTS.sdLowSpaceRatio,
    val rebootLoopCount: Int = HealthThresholds.DEFAULTS.rebootLoopCount,
    val rebootLoopWindow: Duration = HealthThresholds.DEFAULTS.rebootLoopWindow,
    val clockSkewTolerance: Duration = HealthThresholds.DEFAULTS.clockSkewTolerance,
    val offlineAfter: Duration = HealthThresholds.DEFAULTS.offlineAfter,
    val offlineSweepIntervalMs: Long = 30_000,
    val gaugeRefreshMs: Long = 15_000,
) {
    init {
        // Fail fast: a typo here would otherwise raise alerts for the whole fleet.
        require(sdWriteErrorsWarn > 0) { "sd-write-errors-warn must be > 0, got $sdWriteErrorsWarn" }
        require(sdLowSpaceRatio > 0.0 && sdLowSpaceRatio < 1.0) {
            "sd-low-space-ratio is a fraction (0.05 = 5%), got $sdLowSpaceRatio"
        }
        require(rebootLoopCount >= 2) { "reboot-loop-count must be >= 2, got $rebootLoopCount" }
        require(!rebootLoopWindow.isNegative && !rebootLoopWindow.isZero) { "reboot-loop-window must be positive" }
        require(clockSkewTolerance >= Duration.ofSeconds(30)) { "clock-skew-tolerance below 30s would flag network jitter" }
        require(offlineAfter >= Duration.ofMinutes(1)) { "offline-after below 1 min would flap on every missed heartbeat" }
    }

    // No defaults in HealthThresholds: forgetting a field here is now a compile error.
    fun toThresholds() = HealthThresholds(
        sdWriteErrorsWarn = sdWriteErrorsWarn,
        sdLowSpaceRatio = sdLowSpaceRatio,
        rebootLoopCount = rebootLoopCount,
        rebootLoopWindow = rebootLoopWindow,
        clockSkewTolerance = clockSkewTolerance,
        offlineAfter = offlineAfter,
    )
}

@Configuration
class HealthConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun deviceHealthEvaluator(props: HealthProperties): DeviceHealthEvaluator {
        // Effective thresholds in the log: the first thing to check when investigating a health alert.
        log.info("event=health_thresholds {}", props)
        return DeviceHealthEvaluator(props.toThresholds())
    }
}