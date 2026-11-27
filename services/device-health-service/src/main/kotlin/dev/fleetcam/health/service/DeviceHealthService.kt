package dev.fleetcam.health.service

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.health.domain.DeviceHealthEvaluator
import dev.fleetcam.health.domain.Evaluation
import dev.fleetcam.health.metrics.HealthMetrics
import dev.fleetcam.health.persistence.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant

@Service
class DeviceHealthService(
    private val repo: DeviceRepository,
    private val evaluator: DeviceHealthEvaluator,
    private val metrics: HealthMetrics,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun onHeartbeat(hb: HeartbeatReceived) {
        val current = repo.findForUpdate(hb.deviceId)
        // Facts are timestamped when the gateway received them (server clock), not when this consumer
        // processed them: under consumer lag the two differ by minutes, and Support reads this timeline.
        apply(evaluator.onHeartbeat(current, hb), at = hb.receivedAt, now = clock.instant())
    }

    /**
     * Re-reads the device under lock: it may have sent a heartbeat since it was selected as a candidate.
     * Returns true only if the device was actually marked OFFLINE.
     */
    @Transactional
    fun markOfflineIfSilent(deviceId: String, now: Instant): Boolean {
        val current = repo.findForUpdate(deviceId) ?: return false
        val eval = evaluator.onOfflineCheck(current, now) ?: return false
        apply(eval, at = now, now = clock.instant())
        return true
    }

    /**
     * @param at when the facts happened (issues opened/resolved, state transition)
     * @param now when they were processed (row's updated_at)
     */
    private fun apply(eval: Evaluation, at: Instant, now: Instant) {
        val deviceId = eval.snapshot.deviceId.value
        repo.upsert(eval.snapshot, now)
        eval.opened.forEach { repo.openIssue(deviceId, it, at) }
        repo.resolveIssues(deviceId, eval.resolved, at)
        eval.transition?.let { repo.recordTransition(deviceId, it, at) }

        // Metrics and logs must only describe what was actually committed: if the transaction
        // rolls back, a counter that already moved or a logged transition would mislead an investigation.
        afterCommit {
            eval.opened.forEach { metrics.issueOpened(it.type) }
            eval.resolved.forEach { metrics.issueResolved(it) }
            eval.transition?.let {
                // Structured key=value logs are easy to query in Datadog/Loki: `event:state_transition firmware:2.3.1`
                log.info(
                    "event=state_transition device_id={} firmware={} from={} to={} reason=\"{}\"",
                    deviceId, eval.snapshot.firmwareVersion, it.from, it.to, it.reason,
                )
            }
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                },
            )
        } else {
            action() // no surrounding transaction (e.g. unit tests)
        }
    }
}
