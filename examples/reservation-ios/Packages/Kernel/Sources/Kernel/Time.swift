public struct EpochMillis: Hashable, Comparable, Sendable {
    public let value: Int64

    public init(_ value: Int64) { self.value = value }

    public static func < (lhs: EpochMillis, rhs: EpochMillis) -> Bool { lhs.value < rhs.value }

    public func advanced(byMillis millis: Int64) -> EpochMillis { EpochMillis(value + millis) }
}

public protocol Clock: Sendable {
    func now() -> EpochMillis
}
