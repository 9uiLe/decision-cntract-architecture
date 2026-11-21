// swift-tools-version: 6.0
import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .enableUpcomingFeature("ExistentialAny"),
    .enableUpcomingFeature("InternalImportsByDefault"),
    .enableUpcomingFeature("MemberImportVisibility"),
]

let package = Package(
    name: "Kernel",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "Kernel", targets: ["Kernel"]),
        .library(name: "KernelTestSupport", targets: ["KernelTestSupport"]),
    ],
    targets: [
        .target(name: "Kernel", swiftSettings: swiftSettings),
        .target(name: "KernelTestSupport", dependencies: ["Kernel"], swiftSettings: swiftSettings),
        .testTarget(name: "KernelTests", dependencies: ["Kernel", "KernelTestSupport"], swiftSettings: swiftSettings),
    ]
)
