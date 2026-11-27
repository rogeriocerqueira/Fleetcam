package dev.fleetcam.health.persistence

import dev.fleetcam.health.domain.DetectedIssue
import dev.fleetcam.health.domain.DeviceId
import dev.fleetcam.health.domain.DeviceSnapshot
import dev.fleetcam.health.domain.DeviceState
import dev.fleetcam.health.domain.IssueType
import dev.fleetcam.health.domain.StateTransition
import dev.fleetcam.health.presentation.toSupportText
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class IssueRecord(
    val deviceId: String,
    val type: IssueType,
    val severity: String,
    val detail: String,
    val openedAt: Instant,
    val resolvedAt: Instant?,
)

data class TransitionRecord(val from: DeviceState?, val to: DeviceState, val reason: String, val at: Instant)

@Repository
class DeviceRepository(private val jdbc: JdbcClient) {

    /** Row lock serializes concurrent writers for the same device (heartbeat consumer vs offline sweeper). */
    fun findForUpdate(deviceId: String): DeviceSnapshot? = loadWithIssues(deviceId, lock = true)

    fun findById(deviceId: String): DeviceSnapshot? = loadWithIssues(deviceId, lock = false)

    /**
     * The device row and its open issue types in ONE round trip (it used to take two: the row, then
     * the issues). Every heartbeat pays this read, so it is the cheapest place to save a query.
     * The subquery only reads issues; the FOR UPDATE lock still applies to the device row alone.
     */
    private fun loadWithIssues(deviceId: String, lock: Boolean): DeviceSnapshot? =
        jdbc.sql(if (lock) "$SNAPSHOT_WITH_ISSUES for update" else SNAPSHOT_WITH_ISSUES)
            .param("id", deviceId)
            .query { rs, _ -> rs.toSnapshot(rs.openIssueTypes()) }
            .optional()
            .orElse(null)

    /** Filters on the persisted state column; open issues are loaded in one extra query (no N+1). */
    fun search(state: DeviceState?, issue: IssueType?, limit: Int): List<DeviceSnapshot> {
        val devices = jdbc.sql(
            """
            select d.* from device d
            where (cast(:state as text) is null or d.state = :state)
              and (cast(:issue as text) is null or exists (
                    select 1 from device_health_issue i
                    where i.device_id = d.device_id and i.type = :issue and i.resolved_at is null))
            order by d.last_seen_at desc
            limit :limit
            """.trimIndent(),
        )
            .param("state", state?.name)
            .param("issue", issue?.name)
            .param("limit", limit)
            .query { rs, _ -> rs.toSnapshot(emptySet()) }
            .list()
        val issuesByDevice = openIssueRecords(devices.map { it.deviceId.value })
            .groupBy({ it.deviceId }, { it.type })
        return devices.map { it.copy(openIssues = issuesByDevice[it.deviceId.value].orEmpty().toSet()) }
    }

    fun upsert(s: DeviceSnapshot, now: Instant) {
        jdbc.sql(
            """
            insert into device (device_id, firmware_version, state, last_seen_at, last_device_time, last_uptime_seconds,
                                sd_total_bytes, sd_free_bytes, sd_read_only, sd_write_errors, network, signal_dbm,
                                reboot_window_started_at, reboots_in_window, updated_at)
            values (:deviceId, :firmware, :state, :lastSeenAt, :lastDeviceTime, :uptime,
                    :sdTotal, :sdFree, :sdReadOnly, :sdWriteErrors, :network, :signal,
                    :rebootWindow, :reboots, :now)
            on conflict (device_id) do update set
                firmware_version = excluded.firmware_version,
                state = excluded.state,
                last_seen_at = excluded.last_seen_at,
                last_device_time = excluded.last_device_time,
                last_uptime_seconds = excluded.last_uptime_seconds,
                sd_total_bytes = excluded.sd_total_bytes,
                sd_free_bytes = excluded.sd_free_bytes,
                sd_read_only = excluded.sd_read_only,
                sd_write_errors = excluded.sd_write_errors,
                network = excluded.network,
                signal_dbm = excluded.signal_dbm,
                reboot_window_started_at = excluded.reboot_window_started_at,
                reboots_in_window = excluded.reboots_in_window,
                updated_at = excluded.updated_at
            """.trimIndent(),
        )
            .param("deviceId", s.deviceId.value)
            .param("firmware", s.firmwareVersion)
            .param("state", s.state.name)
            .param("lastSeenAt", s.lastSeenAt.toOdt())
            .param("lastDeviceTime", s.lastDeviceTime.toOdt())
            .param("uptime", s.lastUptimeSeconds)
            .param("sdTotal", s.sdTotalBytes)
            .param("sdFree", s.sdFreeBytes)
            .param("sdReadOnly", s.sdReadOnly)
            .param("sdWriteErrors", s.sdWriteErrors)
            .param("network", s.network)
            .param("signal", s.signalDbm)
            .param("rebootWindow", s.rebootWindowStartedAt?.toOdt())
            .param("reboots", s.rebootsInWindow)
            .param("now", now.toOdt())
            .update()
    }

    fun openIssue(deviceId: String, issue: DetectedIssue, at: Instant) {
        jdbc.sql(
            """
            insert into device_health_issue (device_id, type, severity, detail, opened_at)
            values (:deviceId, :type, :severity, :detail, :at)
            on conflict (device_id, type) where resolved_at is null do nothing
            """.trimIndent(),
        )
            .param("deviceId", deviceId)
            .param("type", issue.type.name)
            .param("severity", issue.type.severity.name)
            .param("detail", issue.detail.toSupportText())
            .param("at", at.toOdt())
            .update()
    }

    fun resolveIssues(deviceId: String, types: Set<IssueType>, at: Instant) {
        if (types.isEmpty()) return
        jdbc.sql("update device_health_issue set resolved_at = :at where device_id = :deviceId and type in (:types) and resolved_at is null")
            .param("at", at.toOdt())
            .param("deviceId", deviceId)
            .param("types", types.map { it.name })
            .update()
    }

    fun recordTransition(deviceId: String, t: StateTransition, at: Instant) {
        jdbc.sql("insert into device_state_transition (device_id, from_state, to_state, reason, at) values (:deviceId, :from, :to, :reason, :at)")
            .param("deviceId", deviceId)
            .param("from", t.from?.name)
            .param("to", t.to.name)
            .param("reason", t.reason)
            .param("at", at.toOdt())
            .update()
    }

    /** Oldest silence first, bounded: after an outage the backlog is drained over several sweeps. */
    fun findOfflineCandidates(cutoff: Instant, limit: Int): List<String> =
        jdbc.sql("select device_id from device where state <> 'OFFLINE' and last_seen_at < :cutoff order by last_seen_at limit :limit")
            .param("cutoff", cutoff.toOdt())
            .param("limit", limit)
            .query(String::class.java)
            .list()

    /**
     * Watermark: receive time (server clock) of the newest heartbeat already processed.
     * Null when there are no devices yet.
     */
    fun latestSeenAt(): Instant? =
        jdbc.sql("select max(last_seen_at) as latest from device")
            .query { rs, _ -> rs.instant("latest") }
            .list()
            .firstOrNull()

    fun openIssueRecords(deviceIds: Collection<String>): List<IssueRecord> {
        if (deviceIds.isEmpty()) return emptyList()
        return jdbc.sql("select * from device_health_issue where device_id in (:ids) and resolved_at is null order by opened_at")
            .param("ids", deviceIds)
            .query { rs, _ -> rs.toIssue() }
            .list()
    }

    fun transitions(deviceId: String, limit: Int): List<TransitionRecord> =
        jdbc.sql("select * from device_state_transition where device_id = :id order by at desc limit :limit")
            .param("id", deviceId)
            .param("limit", limit)
            .query { rs, _ ->
                TransitionRecord(
                    from = rs.getString("from_state")?.let { DeviceState.valueOf(it) },
                    to = DeviceState.valueOf(rs.getString("to_state")),
                    reason = rs.getString("reason"),
                    at = rs.instant("at")!!,
                )
            }
            .list()

    fun countByState(): Map<DeviceState, Long> =
        jdbc.sql("select state, count(*) as n from device group by state")
            .query { rs, _ -> DeviceState.valueOf(rs.getString("state")) to rs.getLong("n") }
            .list()
            .toMap()

    fun countOpenIssuesByType(): Map<IssueType, Long> =
        jdbc.sql("select type, count(*) as n from device_health_issue where resolved_at is null group by type")
            .query { rs, _ -> IssueType.valueOf(rs.getString("type")) to rs.getLong("n") }
            .list()
            .toMap()

    private fun ResultSet.toSnapshot(openIssues: Set<IssueType>) = DeviceSnapshot(
        deviceId = DeviceId(getString("device_id")),
        firmwareVersion = getString("firmware_version"),
        lastSeenAt = instant("last_seen_at")!!,
        lastDeviceTime = instant("last_device_time")!!,
        lastUptimeSeconds = getLong("last_uptime_seconds"),
        sdTotalBytes = getLong("sd_total_bytes"),
        sdFreeBytes = getLong("sd_free_bytes"),
        sdReadOnly = getBoolean("sd_read_only"),
        sdWriteErrors = getInt("sd_write_errors"),
        network = getString("network"),
        signalDbm = getInt("signal_dbm"),
        rebootWindowStartedAt = instant("reboot_window_started_at"),
        rebootsInWindow = getInt("reboots_in_window"),
        openIssues = openIssues,
    )

    private fun ResultSet.toIssue() = IssueRecord(
        deviceId = getString("device_id"),
        type = IssueType.valueOf(getString("type")),
        severity = getString("severity"),
        detail = getString("detail"),
        openedAt = instant("opened_at")!!,
        resolvedAt = instant("resolved_at"),
    )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun Instant.toOdt(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    /** Open issue types from the `open_issue_types` column of [SNAPSHOT_WITH_ISSUES]. */
    private fun ResultSet.openIssueTypes(): Set<IssueType> =
        (getArray("open_issue_types").array as Array<*>).mapTo(mutableSetOf()) { IssueType.valueOf(it as String) }

    private companion object {
        val SNAPSHOT_WITH_ISSUES = """
            select d.*,
                   coalesce((select array_agg(i.type) from device_health_issue i
                             where i.device_id = d.device_id and i.resolved_at is null), '{}') as open_issue_types
            from device d
            where d.device_id = :id
            """.trimIndent()
    }
}
