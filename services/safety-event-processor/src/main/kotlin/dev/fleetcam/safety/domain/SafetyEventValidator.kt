package dev.fleetcam.safety.domain

import dev.fleetcam.contracts.events.SafetyEventReceived

enum class SafetyEventType { DROWSINESS, PHONE_USAGE, HARSH_BRAKING, FORWARD_COLLISION_WARNING, SMOKING }

/** Permanently invalid: retrying will never succeed, so the record goes straight to the DLT. */
class InvalidSafetyEventException(val messageId: String, val violations: List<String>) :
    RuntimeException("invalid safety event $messageId: ${violations.joinToString("; ")}")

object SafetyEventValidator {

    private val knownTypes = SafetyEventType.entries.map { it.name }.toSet()

    fun violations(e: SafetyEventReceived): List<String> = buildList {
        if (e.type !in knownTypes) add("unknown type '${e.type}'")
        if (e.confidence.isNaN() || e.confidence !in 0.0..1.0) add("confidence out of range: ${e.confidence}")
        if (e.latitude !in -90.0..90.0) add("latitude out of range: ${e.latitude}")
        if (e.longitude !in -180.0..180.0) add("longitude out of range: ${e.longitude}")
        // (0,0) is in the Atlantic Ocean: it's the classic "GPS without fix" value.
        if (e.latitude == 0.0 && e.longitude == 0.0) add("GPS without fix (0,0)")
        if (e.speedKmh < 0 || e.speedKmh > 250) add("speed out of range: ${e.speedKmh}")
    }

    fun requireValid(e: SafetyEventReceived) {
        val v = violations(e)
        if (v.isNotEmpty()) throw InvalidSafetyEventException(e.messageId, v)
    }
}
