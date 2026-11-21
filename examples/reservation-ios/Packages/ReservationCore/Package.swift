// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

let package = Package(
    name: "ReservationCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "ReservationCore", targets: ["ReservationCore"]),
        .library(name: "ReservationCoreTestSupport", targets: ["ReservationCoreTestSupport"]),
    ],
    dependencies: [.package(path: "../Kernel"), .package(path: "../EligibilityCore")],
    targets: [
        .target(
            name: "ReservationCore",
            dependencies: [
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
            ],
            swiftSettings: swiftSettings
        ),
        // 来場受付 Core のテストが資格 Core に依存せず予約 Core を組み立てるための入口。資格はいつも確認できない。
        .target(
            name: "ReservationCoreTestSupport",
            dependencies: [
                "ReservationCore",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
            ],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "ReservationCoreTests",
            dependencies: [
                "ReservationCore",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "KernelTestSupport", package: "Kernel"),
                .product(name: "EligibilityCore", package: "EligibilityCore"),
            ],
            swiftSettings: swiftSettings
        ),
    ]
)
