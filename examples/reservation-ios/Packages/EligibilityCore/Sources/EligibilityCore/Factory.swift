public import Kernel

public func makeEligibilityCore(
    port: any EligibilityPort,
    clock: any Clock,
    timeout: any OperationTimeout
) -> EligibilityCore {
    EligibilityCore(port: port, clock: clock, timeout: timeout)
}
