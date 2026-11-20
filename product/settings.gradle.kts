pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dca-reservation"

include(
    ":reservation-ports",
    ":reservation-core",
    ":adapter-simulator",
    ":ui",
    ":app",
    ":detekt-rules",
    ":conformance",
)
