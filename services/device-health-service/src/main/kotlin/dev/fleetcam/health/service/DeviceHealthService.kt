@Transactional
fun onHeartbeat(hb: HeartbeatReceived) {
    val current = repo.findForUpdate(hb.deviceId)
    // Facts are timestamped when the gateway received them (server clock), not when we processed them:
    // under consumer lag the two can differ by minutes, and Support reads this timeline.
    apply(evaluator.onHeartbeat(current, hb), at = hb.receivedAt, now = clock.instant())
}

@Transactional
fun markOfflineIfSilent(deviceId: String, now: Instant) {
    val current = repo.findForUpdate(deviceId) ?: return
    evaluator.onOfflineCheck(current, now)?.let { apply(it, at = now, now = now) }
}

private fun apply(eval: Evaluation, at: Instant, now: Instant) {
    val deviceId = eval.snapshot.deviceId
    repo.upsert(eval.snapshot, now)
    eval.opened.forEach { repo.openIssue(deviceId, it, at) }
    repo.resolveIssues(deviceId, eval.resolved, at)
    eval.transition?.let { repo.recordTransition(deviceId, it, at) }

    // Metrics and logs only describe what was actually committed.
    afterCommit {
        eval.opened.forEach { metrics.issueOpened(it.type) }
        eval.resolved.forEach { metrics.issueResolved(it) }
        eval.transition?.let {
            log.info("event=state_transition device_id={} firmware={} from={} to={} reason=\"{}\"",
                deviceId, eval.snapshot.firmwareVersion, it.from, it.to, it.reason)
        }
    }
}

private fun afterCommit(action: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    } else {
        action()
    }
}