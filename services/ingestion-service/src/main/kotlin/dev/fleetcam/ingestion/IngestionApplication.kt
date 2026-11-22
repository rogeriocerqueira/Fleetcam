package dev.fleetcam.ingestion

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
class IngestionApplication

fun main(args: Array<String>) {
    runApplication<IngestionApplication>(*args)
}

@ConfigurationProperties("fleetcam.ingestion")
data class IngestionProperties @ConstructorBinding constructor(
    val grpcPort: Int = 9090,
    val maxBatchSize: Int = 500,
    val topicPartitions: Int = 6,
)

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
