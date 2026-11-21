public struct SessionId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) { self.value = value }
}

public struct ParticipantId: Hashable, Sendable {
    public let value: String

    public init(_ value: String) { self.value = value }
}
