public import Kernel
import os

public final class ManualTimeout: OperationTimeout {
    private struct Waiters: Sendable {
        var generation = 0
        var continuations: [UInt64: (generation: Int, continuation: CheckedContinuation<Bool, Never>)] = [:]
        var last: UInt64 = 0
    }

    private let state = OSAllocatedUnfairLock(initialState: Waiters())

    public init() {}

    public func expire() {
        let resuming = state.withLock { waiters -> [CheckedContinuation<Bool, Never>] in
            waiters.generation += 1
            let ready = waiters.continuations.filter { $0.value.generation < waiters.generation }
            for key in ready.keys { waiters.continuations.removeValue(forKey: key) }
            return ready.values.map(\.continuation)
        }
        for continuation in resuming { continuation.resume(returning: true) }
    }

    public func run<T: Sendable>(_ operation: @escaping @Sendable () async -> T) async -> T? {
        let startedAt = state.withLock { $0.generation }
        return await raceAgainstDeadline(operation) { [self] in await waitForExpiry(startedAt: startedAt) }
    }

    private func waitForExpiry(startedAt: Int) async -> Bool {
        let id = state.withLock { waiters -> UInt64 in
            waiters.last += 1
            return waiters.last
        }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                // キャンセルの旗は onCancel より先に立つ。登録と同じ区間で確認し、登録前に走った onCancel を取りこぼさない。
                let immediate = state.withLock { waiters -> Bool? in
                    if Task.isCancelled { return false }
                    if waiters.generation > startedAt { return true }
                    waiters.continuations[id] = (startedAt, continuation)
                    return nil
                }
                if let immediate { continuation.resume(returning: immediate) }
            }
        } onCancel: {
            let cancelled = state.withLock { $0.continuations.removeValue(forKey: id) }
            cancelled?.continuation.resume(returning: false)
        }
    }
}
