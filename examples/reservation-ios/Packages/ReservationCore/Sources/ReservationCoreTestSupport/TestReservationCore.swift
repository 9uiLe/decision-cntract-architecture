public import Kernel
public import ReservationCore
import EligibilityCore

private struct UnavailableQualificationPort: EligibilityPort {
    func fetchQualification(participant: ParticipantId, kind: QualificationKind) async -> QualificationObservation {
        .unavailable
    }
}

public func makeTestReservationCore(
    catalog: Catalog,
    port: any ReservationPort,
    clock: any Clock,
    newRequestId: @escaping @Sendable () -> RequestId,
    timeout: any OperationTimeout
) -> ReservationCore {
    makeReservationCore(
        catalog: catalog,
        port: port,
        eligibility: makeEligibilityCore(port: UnavailableQualificationPort(), clock: clock, timeout: timeout),
        clock: clock,
        newRequestId: newRequestId,
        timeout: timeout
    )
}
