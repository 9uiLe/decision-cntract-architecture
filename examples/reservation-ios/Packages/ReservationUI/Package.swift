// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

let package = Package(
    name: "ReservationUI",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "ReservationUI", targets: ["ReservationUI"])],
    dependencies: [
        .package(path: "../Kernel"),
        .package(path: "../EligibilityCore"),
        .package(path: "../ReservationCore"),
        .package(path: "../CheckInCore"),
    ],
    targets: [
        .target(
            name: "ReservationUI",
            dependencies: [
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "CheckInCore", package: "CheckInCore"),
            ],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "ReservationUITests",
            dependencies: [
                "ReservationUI",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "KernelTestSupport", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "ReservationCoreTestSupport", package: "ReservationCore"),
                .product(name: "CheckInCore", package: "CheckInCore"),
            ],
            swiftSettings: swiftSettings
        ),
    ]
)
