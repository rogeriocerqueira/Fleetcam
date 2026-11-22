package dev.fleetcam.ingestion.kafka

import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.ingestion.IngestionProperties
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * The ingestion service owns the raw device topics. Replication factor 1 is only
 * acceptable for the local single-broker setup; production would use 3 with min.insync.replicas=2.
 */
@Configuration
class KafkaTopicsConfig(private val props: IngestionProperties) {

    @Bean
    fun heartbeatsTopic(): NewTopic =
        TopicBuilder.name(Topics.HEARTBEATS).partitions(props.topicPartitions).replicas(1).build()

    @Bean
    fun safetyEventsTopic(): NewTopic =
        TopicBuilder.name(Topics.SAFETY_EVENTS).partitions(props.topicPartitions).replicas(1).build()
}
