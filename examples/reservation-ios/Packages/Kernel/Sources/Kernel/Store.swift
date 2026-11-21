import os

// 購読者への公開を状態の更新と同じ排他区間に置く。購読開始時点の値と以後の通知の順序を一貫させるため。
// 区間の中では await せず、外部への呼び出しは区間の外で行う。
public final class Store<State: Sendable, Snapshot: Sendable>: Sendable {
    private struct Inner: Sendable {
        var state: State
        var snapshot: Snapshot
        var subscribers: [UInt64: AsyncStream<Snapshot>.Continuation] = [:]
        var lastSubscriber: UInt64 = 0
    }

    private let inner: OSAllocatedUnfairLock<Inner>
    private let project: @Sendable (State) -> Snapshot

    public init(_ initial: State, project: @escaping @Sendable (State) -> Snapshot) {
        self.project = project
        inner = OSAllocatedUnfairLock(initialState: Inner(state: initial, snapshot: project(initial)))
    }

    public var snapshot: Snapshot { inner.withLock { $0.snapshot } }

    public func read<R: Sendable>(_ body: @Sendable (State) -> R) -> R {
        inner.withLock { body($0.state) }
    }

    public func mutate<R: Sendable>(_ body: @Sendable (inout State) -> R) -> R {
        let project = project
        return inner.withLock { inner in
            let result = body(&inner.state)
            inner.snapshot = project(inner.state)
            for continuation in inner.subscribers.values { continuation.yield(inner.snapshot) }
            return result
        }
    }

    public func updates() -> AsyncStream<Snapshot> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = inner.withLock { inner -> UInt64 in
                inner.lastSubscriber += 1
                inner.subscribers[inner.lastSubscriber] = continuation
                continuation.yield(inner.snapshot)
                return inner.lastSubscriber
            }
            continuation.onTermination = { [weak self] _ in
                self?.inner.withLock { _ = $0.subscribers.removeValue(forKey: id) }
            }
        }
    }
}
