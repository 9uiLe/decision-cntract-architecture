import os

public final class CallLog<Call: Sendable>: Sendable {
    private struct State: Sendable {
        var calls: [Call] = []
        var waiters: [(count: Int, continuation: CheckedContinuation<Void, Never>)] = []
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    public init() {}

    public var calls: [Call] { state.withLock { $0.calls } }

    public var count: Int { state.withLock { $0.calls.count } }

    public func record(_ call: Call) {
        let ready = state.withLock { state -> [CheckedContinuation<Void, Never>] in
            state.calls.append(call)
            let satisfied = state.waiters.filter { $0.count <= state.calls.count }
            state.waiters.removeAll { $0.count <= state.calls.count }
            return satisfied.map(\.continuation)
        }
        for continuation in ready { continuation.resume() }
    }

    public func waitUntil(count: Int) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let satisfied = state.withLock { state -> Bool in
                if state.calls.count >= count { return true }
                state.waiters.append((count, continuation))
                return false
            }
            if satisfied { continuation.resume() }
        }
    }
}
