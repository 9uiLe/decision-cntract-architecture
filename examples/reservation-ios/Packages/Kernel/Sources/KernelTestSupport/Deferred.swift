import os

public final class Deferred<Value: Sendable>: Sendable {
    private struct State: Sendable {
        var value: Value?
        var waiters: [UInt64: CheckedContinuation<Value?, Never>] = [:]
        var last: UInt64 = 0
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    public init() {}

    public func complete(_ value: Value) {
        let waiting = state.withLock { state -> [CheckedContinuation<Value?, Never>] in
            guard state.value == nil else { return [] }
            state.value = value
            defer { state.waiters = [:] }
            return Array(state.waiters.values)
        }
        for continuation in waiting { continuation.resume(returning: value) }
    }

    public func wait() async -> Value? {
        let id = state.withLock { state -> UInt64 in
            state.last += 1
            return state.last
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Value?, Never>) in
                // キャンセルの旗は onCancel より先に立つ。登録と同じ区間で確認し、登録前に走った onCancel を取りこぼさない。
                let immediate = state.withLock { state -> Value?? in
                    if let value = state.value { return .some(value) }
                    if Task.isCancelled { return .some(nil) }
                    state.waiters[id] = continuation
                    return nil
                }
                if let immediate { continuation.resume(returning: immediate) }
            }
        } onCancel: {
            let cancelled = state.withLock { $0.waiters.removeValue(forKey: id) }
            cancelled?.resume(returning: nil)
        }
    }
}
