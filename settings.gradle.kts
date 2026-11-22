rootProject.name = "fleetcam"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "contracts",
    "device-simulator",
    "services:ingestion-service",
    "services:device-health-service",
    "services:safety-event-processor",
)
