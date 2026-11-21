// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

let package = Package(
    name: "SimulatorAdapter",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "SimulatorAdapter", targets: ["SimulatorAdapter"])],
    dependencies: [
        .package(path: "../Kernel"),
        .package(path: "../EligibilityCore"),
        .package(path: "../ReservationCore"),
        .package(path: "../CheckInCore"),
    ],
    targets: [
        .target(
            name: "SimulatorAdapter",
            dependencies: [
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "CheckInCore", package: "CheckInCore"),
            ],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "SimulatorAdapterTests",
            dependencies: [
                "SimulatorAdapter",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "KernelTestSupport", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "CheckInCore", package: "CheckInCore"),
            ],
            swiftSettings: swiftSettings
        ),
    ]
)
