package dev.fleetcam.safety

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@SpringBootApplication
@ConfigurationPropertiesScan
class SafetyApplication

fun main(args: Array<String>) {
    runApplication<SafetyApplication>(*args)
}

@ConfigurationProperties("fleetcam.safety")
data class SafetyProperties(
    /** Below this estimated |clock offset| the device time is trusted as-is. */
    val clockCorrectionTolerance: Duration = Duration.ofMinutes(5),
    val dltPartitions: Int = 6,
)

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
