package dev.fleetcam.health

import dev.fleetcam.health.domain.DeviceHealthEvaluator
import dev.fleetcam.health.domain.HealthThresholds
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.bind.ConstructorBinding
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
data class HealthProperties @ConstructorBinding constructor(
    val sdWriteErrorsWarn: Int = 10,
    val sdLowSpaceRatio: Double = 0.05,
    val rebootLoopCount: Int = 3,
    val rebootLoopWindow: Duration = Duration.ofMinutes(30),
    val clockSkewTolerance: Duration = Duration.ofMinutes(5),
    val offlineAfter: Duration = Duration.ofMinutes(5),
) {
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
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun deviceHealthEvaluator(props: HealthProperties) = DeviceHealthEvaluator(props.toThresholds())
}
