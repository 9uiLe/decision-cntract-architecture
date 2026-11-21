public import EligibilityCore
public import Kernel

public func makeReservationCore(
    catalog: Catalog,
    port: any ReservationPort,
    eligibility: any EligibilityAuthority,
    clock: any Clock,
    newRequestId: @escaping @Sendable () -> RequestId,
    timeout: any OperationTimeout
) -> ReservationCore {
    ReservationCore(
        catalog: catalog, port: port, eligibility: eligibility, clock: clock, newRequestId: newRequestId, timeout: timeout
    )
}
