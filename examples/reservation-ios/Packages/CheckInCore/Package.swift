// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

// 基準仕様の来場受付 Core は、予約 Core の公開契約だけに依存する。資格 Core への依存を加えない。
let package = Package(
    name: "CheckInCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CheckInCore", targets: ["CheckInCore"])],
    dependencies: [.package(path: "../Kernel"), .package(path: "../ReservationCore")],
    targets: [
        .target(
            name: "CheckInCore",
            dependencies: [
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "ReservationCore", package: "ReservationCore"),
            ],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "CheckInCoreTests",
            dependencies: [
                "CheckInCore",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "KernelTestSupport", package: "Kernel"),
                .product(name: "ReservationCore", package: "ReservationCore"),
                .product(name: "ReservationCoreTestSupport", package: "ReservationCore"),
            ],
            swiftSettings: swiftSettings
        ),
    ]
)
