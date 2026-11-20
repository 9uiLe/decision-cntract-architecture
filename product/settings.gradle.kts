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
    ":kernel",
    ":eligibility-core",
    ":reservation-core",
    ":checkin-core",
    ":adapter-simulator",
    ":ui",
    ":shared",
    ":app",
    ":detekt-rules",
    ":conformance",
    ":compile-contracts",
)
