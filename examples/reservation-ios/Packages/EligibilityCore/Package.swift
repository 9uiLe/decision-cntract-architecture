// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

let package = Package(
    name: "EligibilityCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "EligibilityCore", targets: ["EligibilityCore"])],
    dependencies: [.package(path: "../Kernel")],
    targets: [
        .target(
            name: "EligibilityCore",
            dependencies: [.product(name: "Kernel", package: "Kernel")],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "EligibilityCoreTests",
            dependencies: [
                "EligibilityCore",
                .product(name: "Kernel", package: "Kernel"),
                .product(name: "KernelTestSupport", package: "Kernel"),
            ],
            swiftSettings: swiftSettings
        ),
    ]
)
