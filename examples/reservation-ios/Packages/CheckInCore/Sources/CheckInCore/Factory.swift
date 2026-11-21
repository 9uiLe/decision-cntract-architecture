public import Kernel
public import ReservationCore

public func makeCheckInCore(
    source: any AdmissionSource,
    port: any CheckInPort,
    clock: any Clock,
    newAttemptId: @escaping @Sendable () -> CheckInAttemptId,
    timeout: any OperationTimeout
) -> CheckInCore {
    CheckInCore(source: source, port: port, clock: clock, newAttemptId: newAttemptId, timeout: timeout)
}
