public import Kernel

public final class ControllableClock: Clock {
    private let initial: EpochMillis
    private let store: Store<EpochMillis, EpochMillis>

    public init(_ initial: EpochMillis) {
        self.initial = initial
        store = Store(initial) { $0 }
    }

    public func now() -> EpochMillis { store.snapshot }

    public func set(_ time: EpochMillis) { store.mutate { $0 = time } }

    public func advance(millis: Int64) { store.mutate { $0 = $0.advanced(byMillis: millis) } }

    public func reset() { store.mutate { [initial] in $0 = initial } }

    public func updates() -> AsyncStream<EpochMillis> { store.updates() }

    public func changes() -> AsyncStream<Void> {
        let source = store.updates()
        return AsyncStream { continuation in
            let forwarding = Task {
                for await _ in source { continuation.yield() }
                continuation.finish()
            }
            continuation.onTermination = { _ in forwarding.cancel() }
        }
    }
}
