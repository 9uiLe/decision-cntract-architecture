// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ArchitectureCheck",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "architecture-check", targets: ["architecture-check"])],
    targets: [
        .target(name: "ArchitectureRules"),
        .executableTarget(name: "architecture-check", dependencies: ["ArchitectureRules"]),
        .testTarget(name: "ArchitectureRulesTests", dependencies: ["ArchitectureRules"]),
    ]
)
