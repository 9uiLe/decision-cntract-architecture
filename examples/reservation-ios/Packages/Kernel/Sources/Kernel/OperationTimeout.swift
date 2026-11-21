// 打ち切りは Task のキャンセルで伝える。Port の実装はキャンセルに応答して戻らなければ、呼び出しが残り続ける。
// 時間切れの勝敗を実時間に固定しないため、テストは ManualTimeout で決める。
public protocol OperationTimeout: Sendable {
    func run<T: Sendable>(_ operation: @escaping @Sendable () async -> T) async -> T?
}

extension OperationTimeout {
    public func observe<T: Sendable>(unconfirmed: T, _ operation: @escaping @Sendable () async -> T) async -> T {
        await run(operation) ?? unconfirmed
    }
}

public struct WallClockTimeout: OperationTimeout {
    private let duration: Duration

    public init(_ duration: Duration) { self.duration = duration }

    public func run<T: Sendable>(_ operation: @escaping @Sendable () async -> T) async -> T? {
        let duration = duration
        return await raceAgainstDeadline(operation) {
            do {
                try await Task.sleep(for: duration)
                return true
            } catch {
                return false
            }
        }
    }
}

private enum Race<T: Sendable>: Sendable {
    case finished(T)
    case expired
    case abandoned
}

public func raceAgainstDeadline<T: Sendable>(
    _ operation: @escaping @Sendable () async -> T,
    deadline: @escaping @Sendable () async -> Bool
) async -> T? {
    await withTaskGroup(of: Race<T>.self) { group in
        group.addTask { .finished(await operation()) }
        group.addTask { await deadline() ? .expired : .abandoned }
        var result: T?
        var decided = false
        for await outcome in group {
            if decided { continue }
            switch outcome {
            case .finished(let value):
                result = value
                decided = true
            case .expired:
                decided = true
            case .abandoned:
                continue
            }
            group.cancelAll()
        }
        return result
    }
}
