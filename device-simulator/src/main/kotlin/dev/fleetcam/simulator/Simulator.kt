package dev.fleetcam.simulator

import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Simulates a fleet of dashcams talking to the ingestion gateway over gRPC.
 *
 * Configuration (env vars):
 *   GATEWAY_TARGET   host:port of the gateway             (default localhost:9090)
 *   DEVICES          number of healthy devices            (default 50)
 *   FAULTS           comma-separated fault:count pairs    (default see below)
 *   TICK_SECONDS     seconds between heartbeats           (default 5)
 *   SEED             random seed, for reproducible runs   (default 42)
 *
 * Example: FAULTS=sd_card_degrading:3,firmware_clock_skew:10 ./gradlew :device-simulator:run
 */
private val log = LoggerFactory.getLogger("simulator")

fun main() = runBlocking {
    val config = SimulatorConfig.fromEnv(System.getenv())
    log.info("Starting simulator: {}", config)

    val channel = ManagedChannelBuilder.forTarget(config.gatewayTarget).usePlaintext().build()
    val random = Random(config.seed)

    val devices = buildList {
        var n = 0
        repeat(config.healthyDevices) { add(SimulatedDevice(id(++n), emptySet(), random.nextLong())) }
        config.faults.forEach { (fault, count) ->
            repeat(count) { add(SimulatedDevice(id(++n), setOf(fault), random.nextLong())) }
        }
    }
    log.info("Fleet: {} devices ({} with injected faults)", devices.size, devices.count { it.faults.isNotEmpty() })
    devices.filter { it.faults.isNotEmpty() }.forEach { log.info("  {} -> {}", it.deviceId, it.faults) }

    val gateway = GatewayClient(channel)
    Runtime.getRuntime().addShutdownHook(Thread { channel.shutdown().awaitTermination(5, TimeUnit.SECONDS) })

    devices.forEach { device ->
        launch {
            delay(random.nextLong(config.tick.toMillis())) // spread devices across the tick
            while (true) {
                device.tick(config.tick)
                device.flush(gateway)
                delay(config.tick.toMillis())
            }
        }
    }
}

private fun id(n: Int) = "cam-%04d".format(n)

data class SimulatorConfig(
    val gatewayTarget: String,
    val healthyDevices: Int,
    val faults: Map<Fault, Int>,
    val tick: Duration,
    val seed: Long,
) {
    companion object {
        private const val DEFAULT_FAULTS = "sd_card_degrading:2,flaky_connectivity:4,firmware_clock_skew:8,reboot_loop:1,goes_dark:1,bad_gps:1"

        fun fromEnv(env: Map<String, String>) = SimulatorConfig(
            gatewayTarget = env["GATEWAY_TARGET"] ?: "localhost:9090",
            healthyDevices = env["DEVICES"]?.toInt() ?: 50,
            faults = parseFaults(env["FAULTS"] ?: DEFAULT_FAULTS),
            tick = Duration.ofSeconds(env["TICK_SECONDS"]?.toLong() ?: 5),
            seed = env["SEED"]?.toLong() ?: 42,
        )

        fun parseFaults(spec: String): Map<Fault, Int> =
            spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
                val (name, count) = entry.split(':').let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
                Fault.valueOf(name.uppercase()) to count
            }
    }
}
