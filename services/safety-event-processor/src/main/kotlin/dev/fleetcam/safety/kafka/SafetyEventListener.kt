package dev.fleetcam.safety.kafka

import dev.fleetcam.contracts.events.SafetyEventReceived
import dev.fleetcam.contracts.events.Topics
import dev.fleetcam.safety.service.SafetyEventProcessor
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class SafetyEventListener(private val processor: SafetyEventProcessor) {

    @KafkaListener(topics = [Topics.SAFETY_EVENTS], groupId = "safety-event-processor", concurrency = "3")
    fun onEvent(event: SafetyEventReceived) = processor.process(event)
}
