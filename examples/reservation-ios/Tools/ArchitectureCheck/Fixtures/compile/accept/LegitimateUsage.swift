import CheckInCore
import EligibilityCore
import Kernel
import ReservationCore

func submit(core: ReservationCore, checkIn: CheckInCore) {
    _ = core.selectWorkshop(SessionId("pottery"))
    _ = core.toggleParticipant(ParticipantId("alice"))
    switch core.confirmWithoutQualification() {
    case .confirmed(let draft): _ = core.submit(draft)
    case .noWorkshop, .emptyParticipants, .qualificationRequired, .qualificationNotRequired, .eligibilityNotMet: break
    }
    switch core.snapshot.request {
    case .unknown(let ticket)?: _ = core.checkStatus(ticket)
    case .creating?, .checkingStatus?, .cancelling?, .notCreated?, .soldOut?, .registrationClosed?, .eligibilityLost?, nil: break
    }
    for case .reserved(let record) in core.snapshot.reservations {
        _ = core.cancel(record.cancel)
        for admission in record.admissions { _ = checkIn.checkIn(admission) }
    }
}

func describe(_ result: Reverification) -> String {
    switch result {
    case .verified(let certificates): return "\(certificates.count)"
    case .failed(let reason):
        switch reason {
        case .notHeld, .expired, .unconfirmed, .targetMismatch: return "failed"
        }
    }
}
