public struct Violation: Equatable, Sendable, CustomStringConvertible {
    public let rule: String
    public let location: String
    public let message: String

    public init(rule: String, location: String, message: String) {
        self.rule = rule
        self.location = location
        self.message = message
    }

    public var description: String { "\(location): [\(rule)] \(message)" }
}

// 来場受付 Core は予約 Core の公開契約だけに依存し、資格 Core を見ない。画面は外部サービスの代役を知らない。
public let allowedPackageDependencies: [String: Set<String>] = [
    "Kernel": [],
    "EligibilityCore": ["Kernel"],
    "ReservationCore": ["Kernel", "EligibilityCore"],
    "CheckInCore": ["Kernel", "ReservationCore"],
    "SimulatorAdapter": ["Kernel", "EligibilityCore", "ReservationCore", "CheckInCore"],
    "ReservationUI": ["Kernel", "EligibilityCore", "ReservationCore", "CheckInCore"],
    "Composition": ["Kernel", "EligibilityCore", "ReservationCore", "CheckInCore", "SimulatorAdapter", "ReservationUI"],
]

// SwiftPM は宣言していない推移的な依存の import もコンパイルできるため、宣言した依存だけを import させる。
public let systemModules: Set<String> = ["Foundation", "os", "Combine", "SwiftUI", "Testing", "Synchronization"]

public let appAllowedImports: Set<String> = ["Composition", "SwiftUI", "Foundation"]

public let appAllowedPackages: Set<String> = ["Composition"]
