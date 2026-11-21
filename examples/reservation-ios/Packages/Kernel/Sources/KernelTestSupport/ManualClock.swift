public import Kernel
import os

public final class ManualClock: Clock {
    private let current: OSAllocatedUnfairLock<EpochMillis>

    public init(_ initial: EpochMillis) { current = OSAllocatedUnfairLock(initialState: initial) }

    public func now() -> EpochMillis { current.withLock { $0 } }

    public func set(_ time: EpochMillis) { current.withLock { $0 = time } }

    public func advance(millis: Int64) { current.withLock { $0 = $0.advanced(byMillis: millis) } }
}
