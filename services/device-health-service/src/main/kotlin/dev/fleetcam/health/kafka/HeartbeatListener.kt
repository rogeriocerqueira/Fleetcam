package dev.fleetcam.health.kafka

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.health.service.DeviceHealthService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.stereotype.Component
import org.springframework.util.backoff.ExponentialBackOff

@Component
class HeartbeatListener(private val service: DeviceHealthService) {

    @KafkaListener(topics = [Topics.HEARTBEATS], groupId = "device-health-service", concurrency = "3")
    fun onHeartbeat(hb: HeartbeatReceived) = service.onHeartbeat(hb)
}

@Configuration
class KafkaErrorHandlingConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Heartbeats are periodic and superseded by the next one, so after a few retries we log
     * and skip instead of blocking the partition (a stuck partition = hundreds of devices
     * "going offline" at once — a much worse failure mode than one lost heartbeat).
     */
    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler {
        val backOff = ExponentialBackOff(200, 2.0).apply { maxElapsedTime = 5_000 }
        return DefaultErrorHandler(ConsumerRecordRecoverer { record, ex ->
            log.error("event=heartbeat_skipped topic={} partition={} offset={} key={}", record.topic(), record.partition(), record.offset(), record.key(), ex)
        }, backOff)
    }
}
