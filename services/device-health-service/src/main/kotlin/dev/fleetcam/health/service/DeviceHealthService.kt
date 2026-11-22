package dev.fleetcam.health.service

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.health.domain.DeviceHealthEvaluator
import dev.fleetcam.health.domain.Evaluation
import dev.fleetcam.health.metrics.HealthMetrics
import dev.fleetcam.health.persistence.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
        apply(evaluator.onHeartbeat(current, hb), clock.instant())
    }

    /** Re-reads the device under lock: the device may have sent a heartbeat since it was selected as a candidate. */
    @Transactional
    fun markOfflineIfSilent(deviceId: String, now: Instant) {
        val current = repo.findForUpdate(deviceId) ?: return
        evaluator.onOfflineCheck(current, now)?.let { apply(it, now) }
    }

    private fun apply(eval: Evaluation, now: Instant) {
        val deviceId = eval.snapshot.deviceId
        repo.upsert(eval.snapshot, now)
        eval.opened.forEach {
            repo.openIssue(deviceId, it, now)
            metrics.issueOpened(it.type)
        }
        repo.resolveIssues(deviceId, eval.resolved, now)
        eval.resolved.forEach { metrics.issueResolved(it) }
        eval.transition?.let {
            repo.recordTransition(deviceId, it, now)
            // Structured key=value logs are easy to query in Datadog/Loki: `device_id:cam-0042 event:state_transition`
            log.info("event=state_transition device_id={} from={} to={} reason=\"{}\"", deviceId, it.from, it.to, it.reason)
        }
    }
}
