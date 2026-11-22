plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.grpc.netty.shaded)
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.fleetcam.simulator.SimulatorKt")
}
