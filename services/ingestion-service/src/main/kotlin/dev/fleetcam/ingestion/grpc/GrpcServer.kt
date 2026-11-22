package dev.fleetcam.ingestion.grpc

import dev.fleetcam.ingestion.IngestionProperties
import io.grpc.Grpc
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Runs the gRPC server inside the Spring lifecycle, so it starts after every bean
 * (Kafka producer included) is ready and drains in-flight calls on shutdown.
 *
 * TLS is intentionally off for local development. In the field, devices would use mTLS
 * with a per-device certificate, which also authenticates the device_id.
 */
@Component
class GrpcServer(
    gateway: DeviceGatewayService,
    private val props: IngestionProperties,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val server: Server = Grpc.newServerBuilderForPort(props.grpcPort, InsecureServerCredentials.create())
        .addService(gateway)
        .build()

    @Volatile
    private var running = false

    override fun start() {
        server.start()
        running = true
        log.info("gRPC DeviceGateway listening on port {}", props.grpcPort)
    }

    override fun stop() {
        server.shutdown()
        if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("gRPC server did not drain in 10s, forcing shutdown")
            server.shutdownNow()
        }
        running = false
    }

    override fun isRunning(): Boolean = running
}
