package dev.fleetcam.health

import dev.fleetcam.health.domain.DeviceHealthEvaluator
import dev.fleetcam.health.domain.HealthThresholds
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.Duration

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class HealthApplication

fun main(args: Array<String>) {
    runApplication<HealthApplication>(*args)
}

@ConfigurationProperties("fleetcam.health")
data class HealthProperties(
    val sdWriteErrorsWarn: Int = HealthThresholds.DEFAULTS.sdWriteErrorsWarn,
    val sdLowSpaceRatio: Double = HealthThresholds.DEFAULTS.sdLowSpaceRatio,
    val rebootLoopCount: Int = HealthThresholds.DEFAULTS.rebootLoopCount,
    val rebootLoopWindow: Duration = HealthThresholds.DEFAULTS.rebootLoopWindow,
    val clockSkewTolerance: Duration = HealthThresholds.DEFAULTS.clockSkewTolerance,
    val offlineAfter: Duration = HealthThresholds.DEFAULTS.offlineAfter,
) {
    /** Validation lives in HealthThresholds: an invalid value stops the service at startup with a clear message. */
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
        val thresholds = props.toThresholds()
        // Effective values in the log: the first thing to check when investigating a health alert.
        log.info("event=health_thresholds {}", thresholds)
        return DeviceHealthEvaluator(thresholds)
    }
}
