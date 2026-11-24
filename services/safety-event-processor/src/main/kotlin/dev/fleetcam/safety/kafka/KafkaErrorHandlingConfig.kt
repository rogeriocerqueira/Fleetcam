package dev.fleetcam.safety.kafka

import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.safety.SafetyProperties
import dev.fleetcam.safety.domain.InvalidSafetyEventException
import dev.fleetcam.safety.metrics.SafetyMetrics
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.ExponentialBackOff

/**
 * Error strategy (docs/adr/0003):
 *  - transient failures (DB down, timeout): retry with exponential backoff, then DLT
 *  - permanent failures (validation, undeserializable payload): DLT immediately, no retries
 *
 * Safety events are never silently dropped — every record ends up either in the database or
 * in the DLT, where it can be inspected and replayed after a fix.
 */
@Configuration
class KafkaErrorHandlingConfig(private val props: SafetyProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun safetyEventsDlt(): NewTopic =
        TopicBuilder.name(Topics.SAFETY_EVENTS_DLT).partitions(props.dltPartitions).replicas(1).build()

    /**
     * Dedicated template for the DLT: valid-but-rejected records are objects (JSON), while
     * records that failed deserialization arrive as raw bytes and must be forwarded untouched.
     *
     * Connection settings come from Boot's producer factory (not straight from KafkaProperties),
     * so connection-details overrides such as Testcontainers' @ServiceConnection are honored.
     */
    @Bean
    fun dltTemplate(bootProducerFactory: ProducerFactory<*, *>): KafkaTemplate<String, Any> {
        val valueSerializer = DelegatingByTypeSerializer(
            mapOf<Class<*>, Serializer<*>>(
                ByteArray::class.java to ByteArraySerializer(),
                SafetyEventReceived::class.java to JsonSerializer<Any>().noTypeInfo(),
            ),
        )
        val factory = DefaultKafkaProducerFactory<String, Any>(
            bootProducerFactory.configurationProperties.filterKeys { !it.startsWith("spring.json.") },
            StringSerializer(),
            valueSerializer,
        )
        return KafkaTemplate(factory)
    }

    @Bean
    fun kafkaErrorHandler(dltTemplate: KafkaTemplate<String, Any>, metrics: SafetyMetrics): DefaultErrorHandler {
        val dlpr = DeadLetterPublishingRecoverer(dltTemplate)
        val recoverer = ConsumerRecordRecoverer { record, ex ->
            log.error("event=dead_lettered topic={} partition={} offset={} key={} cause=\"{}\"",
                record.topic(), record.partition(), record.offset(), record.key(), ex.cause?.message ?: ex.message)
            metrics.outcome("dead_lettered")
            dlpr.accept(record, ex)
        }
        val backOff = ExponentialBackOff(500, 2.0).apply {
            maxInterval = 5_000
            maxElapsedTime = 30_000
        }
        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(InvalidSafetyEventException::class.java)
        }
    }
}
