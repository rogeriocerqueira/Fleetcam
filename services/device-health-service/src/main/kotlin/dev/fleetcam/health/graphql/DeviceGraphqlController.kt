package dev.fleetcam.health.graphql

import dev.fleetcam.health.domain.DeviceSnapshot
import dev.fleetcam.health.domain.DeviceState
import dev.fleetcam.health.domain.IssueType
import dev.fleetcam.health.persistence.DeviceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

data class DeviceView(
    val id: String,
    val firmwareVersion: String,
    val state: DeviceState,
    val lastSeenAt: String,
    val sdCard: SdCardView,
    val connectivity: ConnectivityView,
)

data class SdCardView(val totalGb: Double, val freePercent: Double, val readOnly: Boolean, val writeErrorsSinceBoot: Int)
data class ConnectivityView(val network: String, val signalDbm: Int)
data class HealthIssueView(val type: IssueType, val severity: String, val detail: String, val openedAt: String)
data class StateTransitionView(val from: DeviceState?, val to: DeviceState, val reason: String, val at: String)
data class StateCount(val state: DeviceState, val count: Long)
data class IssueCount(val type: IssueType, val count: Long)
data class FleetHealthSummary(val totalDevices: Long, val byState: List<StateCount>, val openIssues: List<IssueCount>)

/**
 * Read API used by the panel and by Support. Explore it at http://localhost:8082/graphiql
 */
@Controller
class DeviceGraphqlController(private val repo: DeviceRepository) {

    @QueryMapping
    fun device(@Argument id: String): DeviceView? = repo.findById(id)?.toView()

    @QueryMapping
    fun devices(@Argument state: DeviceState?, @Argument issue: IssueType?, @Argument first: Int?): List<DeviceView> =
        repo.search(state, issue, (first ?: 50).coerceIn(1, 500)).map { it.toView() }

    @QueryMapping
    fun fleetHealthSummary(): FleetHealthSummary {
        val byState = repo.countByState()
        return FleetHealthSummary(
            totalDevices = byState.values.sum(),
            byState = DeviceState.entries.map { StateCount(it, byState[it] ?: 0) },
            openIssues = repo.countOpenIssuesByType().map { (type, n) -> IssueCount(type, n) }.sortedByDescending { it.count },
        )
    }

    /** One query for all devices in the response instead of one per device (avoids N+1). */
    @BatchMapping(typeName = "Device", field = "openIssues")
    fun openIssues(devices: List<DeviceView>): Map<DeviceView, List<HealthIssueView>> {
        val byDevice = repo.openIssueRecords(devices.map { it.id }).groupBy { it.deviceId }
        return devices.associateWith { d ->
            byDevice[d.id].orEmpty().map { HealthIssueView(it.type, it.severity, it.detail, it.openedAt.toString()) }
        }
    }

    @SchemaMapping(typeName = "Device", field = "transitions")
    fun transitions(device: DeviceView, @Argument last: Int?): List<StateTransitionView> =
        repo.transitions(device.id, (last ?: 10).coerceIn(1, 100))
            .map { StateTransitionView(it.from, it.to, it.reason, it.at.toString()) }

    private fun DeviceSnapshot.toView() = DeviceView(
        id = deviceId,
        firmwareVersion = firmwareVersion,
        state = state,
        lastSeenAt = lastSeenAt.toString(),
        sdCard = SdCardView(
            totalGb = sdTotalBytes / 1e9,
            freePercent = if (sdTotalBytes > 0) sdFreeBytes * 100.0 / sdTotalBytes else 0.0,
            readOnly = sdReadOnly,
            writeErrorsSinceBoot = sdWriteErrors,
        ),
        connectivity = ConnectivityView(network, signalDbm),
    )
}
