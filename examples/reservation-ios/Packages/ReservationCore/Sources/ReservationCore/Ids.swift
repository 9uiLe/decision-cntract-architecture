public struct RequestId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) { self.value = value }
}

public struct ReservationId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) { self.value = value }
}
