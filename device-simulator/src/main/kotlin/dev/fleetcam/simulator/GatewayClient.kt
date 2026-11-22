package dev.fleetcam.simulator

import dev.fleetcam.contracts.telemetry.v1.DeviceGatewayGrpcKt
import dev.fleetcam.contracts.telemetry.v1.TelemetryBatch
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import java.util.concurrent.TimeUnit

sealed interface PublishResult {
    data class Ok(val accepted: Int, val rejected: Int) : PublishResult
    data class Retryable(val reason: String) : PublishResult
    data class Fatal(val reason: String) : PublishResult
}

class GatewayClient(channel: ManagedChannel) {
    private val stub = DeviceGatewayGrpcKt.DeviceGatewayCoroutineStub(channel)

    suspend fun publish(batch: TelemetryBatch): PublishResult = try {
        val response = stub.withDeadlineAfter(5, TimeUnit.SECONDS).publish(batch)
        PublishResult.Ok(response.accepted, response.rejected)
    } catch (e: StatusException) {
        // INVALID_ARGUMENT will never succeed; everything else (UNAVAILABLE, DEADLINE_EXCEEDED...) is retried.
        if (e.status.code == Status.Code.INVALID_ARGUMENT) PublishResult.Fatal(e.status.toString())
        else PublishResult.Retryable(e.status.code.name)
    }
}
