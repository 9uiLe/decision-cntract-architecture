public func firstValue<Value: Sendable>(
    of stream: AsyncStream<Value>,
    where predicate: @Sendable (Value) -> Bool
) async -> Value? {
    for await value in stream where predicate(value) { return value }
    return nil
}
