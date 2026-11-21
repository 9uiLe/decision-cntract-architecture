// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

let package = Package(
    name: "Composition",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "Composition", targets: ["Composition"])],
    dependencies: [
        .package(path: "../Kernel"),
        .package(path: "../EligibilityCore"),
        .package(path: "../ReservationCore"),
        .package(path: "../CheckInCore"),
        .package(path: "../SimulatorAdapter"),
        .package(path: "../ReservationUI"),
    ],
    targets: [
        .target(
            name: "Composition",
            dependencies: [
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "CheckInCore", package: "CheckInCore"),
                .product(name: "SimulatorAdapter", package: "SimulatorAdapter"),
                .product(name: "ReservationUI", package: "ReservationUI"),
            ],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "CompositionTests",
            dependencies: [
                "Composition",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "KernelTestSupport", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "CheckInCore", package: "CheckInCore"),
                .product(name: "SimulatorAdapter", package: "SimulatorAdapter"),
            ],
            swiftSettings: swiftSettings
        ),
    ]
)
