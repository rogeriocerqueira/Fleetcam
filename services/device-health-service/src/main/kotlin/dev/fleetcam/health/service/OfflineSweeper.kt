@Scheduled(fixedDelayString = "\${fleetcam.health.offline-sweep-interval-ms:30000}")
fun sweep() {
    // Judge silence against the newest heartbeat already processed (watermark), not the wall clock:
    // if the consumer lags or was down, heartbeats are still waiting in Kafka, and the wall clock
    // would mark the whole fleet OFFLINE at once.
    val watermark = repo.latestSeenAt() ?: return // no devices yet
    val now = clock.instant()
    val reference = minOf(now, watermark)
    if (Duration.between(watermark, now) > props.offlineAfter) {
        log.warn("event=offline_sweep_behind consumer_lag_s={} (judging by watermark, not wall clock)",
            Duration.between(watermark, now).seconds)
    }

    val sample = Timer.start(registry)
    val candidates = repo.findOfflineCandidates(reference.minus(props.offlineAfter), limit = BATCH_SIZE)
    var marked = 0
    var failed = 0
    candidates.forEach { deviceId ->
        try {
            if (service.markOfflineIfSilent(deviceId, reference)) marked++
        } catch (e: Exception) {
            failed++
            log.error("event=offline_check_failed device_id={}", deviceId, e)
        }
    }
    sample.stop(registry.timer("fleetcam.health.offline.sweep"))
    registry.counter("fleetcam.health.offline.marked").increment(marked.toDouble())
    if (candidates.isNotEmpty()) {
        log.info("event=offline_sweep candidates={} marked={} failed={} batch_full={}",
            candidates.size, marked, failed, candidates.size == BATCH_SIZE)
    }
}

private companion object {
    /** Bounds one sweep after an outage; the rest is picked up on the next runs, oldest first. */
    const val BATCH_SIZE = 500
}