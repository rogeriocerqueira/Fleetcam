package dev.fleetcam.health.domain

import dev.fleetcam.contracts.events.HeartbeatReceived
import dev.fleetcam.contracts.time.ClockMath
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Pure health rules: no I/O, no Spring, no clock. Everything needed is passed in,
 * which makes every field scenario reproducible in a unit test.
 */
class DeviceHealthEvaluator(private val t: HealthThresholds) {

    fun onHeartbeat(current: DeviceSnapshot?, hb: HeartbeatReceived): Evaluation {
        if (current != null && hb.deviceTime.isBefore(current.lastDeviceTime)) {
            // Out-of-order or replayed heartbeat (backlog flushed after reconnecting, or a
            // duplicate after a lost ack). It proves the device was alive, but must never
            // overwrite newer state.
            val refreshed = current.copy(lastSeenAt = maxOf(current.lastSeenAt, hb.receivedAt))
            return Evaluation(refreshed, opened = emptyList(), resolved = emptySet(), transition = null)
        }

        // Reboot = uptime grew less than the time that passed since the previous heartbeat.
        // (A plain "uptime went backwards" check misses fast loops: 500s -> 20s -> 20s looks
        // like only one reboot.) Intervals use the device clock: even a skewed clock measures
        // *intervals* correctly, while receivedAt is compressed for buffered messages.
        val rebooted = current != null && run {
            val elapsed = Duration.between(current.lastDeviceTime, hb.deviceTime).seconds
            hb.uptimeSeconds + REBOOT_TOLERANCE_SECONDS < current.lastUptimeSeconds + elapsed
        }
        var windowStart = current?.rebootWindowStartedAt
        var reboots = current?.rebootsInWindow ?: 0
        if (windowStart != null && Duration.between(windowStart, hb.deviceTime) > t.rebootLoopWindow) {
            windowStart = null
            reboots = 0
        }
        if (rebooted) {
            if (windowStart == null) windowStart = hb.deviceTime
            reboots += 1
        }

        val detected = detectIssues(hb, reboots)
        val openIssues = detected.map { it.type }.toSet()
        val previousOpen = current?.openIssues.orEmpty()
        val opened = detected.filter { it.type !in previousOpen }
        val resolved = previousOpen - openIssues

        val next = DeviceSnapshot(
            deviceId = hb.deviceId,
            firmwareVersion = hb.firmwareVersion,
            state = if (openIssues.isEmpty()) DeviceState.ACTIVE else DeviceState.DEGRADED,
            lastSeenAt = maxOf(current?.lastSeenAt ?: hb.receivedAt, hb.receivedAt),
            lastDeviceTime = hb.deviceTime,
            lastUptimeSeconds = hb.uptimeSeconds,
            sdTotalBytes = hb.sdCard.totalBytes,
            sdFreeBytes = hb.sdCard.freeBytes,
            sdReadOnly = hb.sdCard.readOnly,
            sdWriteErrors = hb.sdCard.writeErrorsSinceBoot,
            network = hb.network,
            signalDbm = hb.signalDbm,
            rebootWindowStartedAt = windowStart,
            rebootsInWindow = reboots,
            openIssues = openIssues,
        )

        val transition = when {
            current == null -> StateTransition(null, next.state, "primeiro heartbeat" + describe(opened, emptySet()))
            current.state != next.state -> StateTransition(current.state, next.state, describe(opened, resolved).trim())
            else -> null
        }
        return Evaluation(next, opened, resolved, transition)
    }

    /** Called periodically by the sweeper. Returns null when nothing changes. */
    fun onOfflineCheck(current: DeviceSnapshot, now: Instant): Evaluation? {
        if (current.state == DeviceState.OFFLINE) return null
        val silence = Duration.between(current.lastSeenAt, now)
        if (silence <= t.offlineAfter) return null

        val issue = DetectedIssue(
            IssueType.OFFLINE,
            "Sem heartbeat há ${silence.toMinutes()} min (último contato ${current.lastSeenAt}). " +
                "Verificar ignição/alimentação do veículo e cobertura de sinal na região.",
        )
        val next = current.copy(state = DeviceState.OFFLINE, openIssues = current.openIssues + IssueType.OFFLINE)
        return Evaluation(
            snapshot = next,
            opened = listOf(issue),
            resolved = emptySet(),
            transition = StateTransition(current.state, DeviceState.OFFLINE, "sem heartbeat há ${silence.toMinutes()} min"),
        )
    }

    private fun detectIssues(hb: HeartbeatReceived, reboots: Int): List<DetectedIssue> = buildList {
        val sd = hb.sdCard
        if (sd.readOnly) {
            add(DetectedIssue(IssueType.SD_CARD_READ_ONLY, "Cartão SD em modo somente leitura: a câmera NÃO está gravando vídeo. Trocar o cartão."))
        }
        if (sd.writeErrorsSinceBoot >= t.sdWriteErrorsWarn) {
            add(DetectedIssue(IssueType.SD_CARD_WRITE_ERRORS, "${sd.writeErrorsSinceBoot} erros de escrita no cartão SD desde o último boot. Risco de perda de vídeos."))
        }
        if (sd.totalBytes > 0) {
            val freeRatio = sd.freeBytes.toDouble() / sd.totalBytes
            if (freeRatio < t.sdLowSpaceRatio) {
                val pct = String.format(Locale.ROOT, "%.1f", freeRatio * 100)
                add(DetectedIssue(IssueType.SD_CARD_LOW_SPACE, "Apenas $pct% livre no cartão SD: a gravação em loop pode não estar liberando espaço."))
            }
        }
        if (reboots >= t.rebootLoopCount) {
            add(DetectedIssue(IssueType.REBOOT_LOOP, "$reboots reinícios em até ${t.rebootLoopWindow.toMinutes()} min. Possível falha de alimentação ou firmware."))
        }
        val offset = ClockMath.estimatedClockOffset(hb)
        if (offset.abs() > t.clockSkewTolerance) {
            val direction = if (offset.isNegative) "adiantado" else "atrasado"
            add(
                DetectedIssue(
                    IssueType.CLOCK_SKEW,
                    "Relógio do dispositivo $direction ~${offset.abs().toMinutes()} min (firmware ${hb.firmwareVersion}). " +
                        "Eventos são exibidos com horário corrigido pelo servidor.",
                ),
            )
        }
    }

    private companion object {
        /** Absorbs heartbeat jitter and clock drift between consecutive heartbeats. */
        const val REBOOT_TOLERANCE_SECONDS = 60L
    }

    private fun describe(opened: List<DetectedIssue>, resolved: Set<IssueType>): String = buildString {
        if (opened.isNotEmpty()) append(" abertos=").append(opened.map { it.type })
        if (resolved.isNotEmpty()) append(" resolvidos=").append(resolved)
    }
}
