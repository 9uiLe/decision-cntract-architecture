public import CheckInCore
public import EligibilityCore
import Kernel
public import ReservationCore

public struct TimeGates: Sendable {
    public let registration: @Sendable (Workshop) -> TimeGate
    public let cancellation: @Sendable (Workshop) -> TimeGate
    public let checkIn: @Sendable (ReservationAdmission) -> CheckInWindow

    public init(
        registration: @escaping @Sendable (Workshop) -> TimeGate,
        cancellation: @escaping @Sendable (Workshop) -> TimeGate,
        checkIn: @escaping @Sendable (ReservationAdmission) -> CheckInWindow
    ) {
        self.registration = registration
        self.cancellation = cancellation
        self.checkIn = checkIn
    }
}

public func project(
    snapshot: CoreSnapshot,
    eligibility: EligibilitySnapshot,
    checkIn: CheckInSnapshot,
    catalog: Catalog,
    gates: TimeGates
) -> ReservationScreenState {
    let names = Dictionary(uniqueKeysWithValues: catalog.participants.map { ($0.id, $0.name) })
    let status = StatusState(
        request: snapshot.request.flatMap { projectRequest($0, names: names, gates: gates) },
        reservations: snapshot.reservations.map {
            projectReservation($0, request: snapshot.request, names: names, checkIn: checkIn, gates: gates)
        }
    )
    let draft = snapshot.draft
    var kind: QualificationKind?
    if case .required(let required)? = draft.workshop?.requirement { kind = required }
    let rows = catalog.participants.map { participant in
        let badge = kind.map { projectBadge(eligibility.statuses[EligibilityKey(participant: participant.id, kind: $0)] ?? .notChecked) }
        return ParticipantRow(
            id: participant.id, name: participant.name, selected: draft.participants.contains(participant.id), qualification: badge
        )
    }
    let selected = rows.filter(\.selected)
    let allValid = selected.allSatisfy { if case .valid? = $0.qualification { true } else { false } }
    let canConfirm = draft.workshop != nil && !selected.isEmpty && (kind == nil || allValid)
    return ReservationScreenState(
        list: ListState(workshops: catalog.workshops.map(item), ongoing: status),
        details: DetailsState(
            workshop: draft.workshop.map(item),
            participants: rows,
            stage: projectStage(draft, gates: gates),
            canConfirm: canConfirm,
            confirmHint: canConfirm || draft.workshop == nil ? nil : confirmHint(hasSelection: !selected.isEmpty),
            canRequestEligibility: kind != nil && !selected.isEmpty
        ),
        status: status
    )
}

private func confirmHint(hasSelection: Bool) -> String {
    hasSelection
        ? "参加者全員の資格が「資格あり」になると、内容を確認できます。「資格を確認する」を押してください。"
        : "参加者を選ぶと、内容を確認できます。"
}

func projectStage(_ draft: Draft, gates: TimeGates) -> DetailsStage {
    switch draft.confirmation {
    case .unconfirmed: return .editing
    case .confirmed(let confirmed): return .confirmed(confirmed, submit: gates.registration(confirmed.workshop))
    }
}

func projectRequirement(_ requirement: NewBookingRequirement) -> RequirementView {
    switch requirement {
    case .notRequired: return .notRequired
    case .required(let kind): return .required(qualificationName: kind.name)
    }
}

func projectBadge(_ status: EligibilityStatus) -> QualificationBadge {
    switch status {
    case .notChecked: return .notChecked
    case .checking: return .checking
    case .settled(let outcome): return projectOutcome(outcome)
    }
}

func projectOutcome(_ outcome: EligibilityOutcome) -> QualificationBadge {
    switch outcome {
    case .eligible: return .valid
    case .notHeld: return .notHeld
    case .expired: return .expired
    case .unknown: return .unknown
    }
}

// 取消の最中は予約のカードが取消中を示す。申込の側は、取消の間も保持している未解決の結果だけを示す。
func projectRequest(_ request: ActiveRequest, names: [ParticipantId: String], gates: TimeGates) -> RequestCard? {
    let summary = request.booking.summary(names)
    switch request {
    case .creating: return .sending(summary)
    case .checkingStatus: return .checkingStatus(summary)
    case .unknown(let ticket): return .unknown(summary, check: ticket)
    case .notCreated(let retry, let denial):
        return .notCreated(summary, retry: retry, denial: denial, gate: gates.registration(retry.draft.workshop))
    case .soldOut: return .soldOut(summary)
    case .registrationClosed: return .registrationClosed(summary)
    case .eligibilityLost(_, let reason): return .eligibilityLost(summary, reason: reason)
    case .cancelling(_, _, let resting?): return projectRequest(resting, names: names, gates: gates)
    case .cancelling(_, _, nil): return nil
    }
}

func projectReservation(
    _ record: ReservationRecord,
    request: ActiveRequest?,
    names: [ParticipantId: String],
    checkIn: CheckInSnapshot,
    gates: TimeGates
) -> ReservationCard {
    let summary = record.booking.summary(names)
    let id = record.reservation.value
    switch record {
    case .reserved(let reserved):
        if case .cancelling(let target, _, _)? = request, target == reserved.reservation {
            return .cancelling(summary: summary, reservationId: id)
        }
        return .reserved(ReservedCard(
            summary: summary,
            reservationId: id,
            cancel: reserved.cancel,
            cancellation: gates.cancellation(reserved.booking.workshop),
            lastCancelFailure: reserved.lastCancelFailure,
            participants: reserved.admissions.map { admission in
                ParticipantCheckInRow(
                    name: names[admission.participant] ?? admission.participant.value,
                    admission: admission,
                    view: projectCheckIn(checkIn.entries[CheckInKey(reservation: reserved.reservation, participant: admission.participant)]),
                    window: gates.checkIn(admission)
                )
            }
        ))
    case .cancelled:
        return .cancelled(summary: summary, reservationId: id)
    }
}

func projectCheckIn(_ entry: ParticipantCheckIn?) -> CheckInView {
    switch entry {
    case nil: return .notCheckedIn
    case .processing(let stage)?: return .processing(stage)
    case .checkedIn?: return .checkedIn
    case .unknown(let ticket)?: return .unknown(ticket)
    case .notCreated(let retry)?: return .notCreated(retry)
    case .notAdmitted(let denial)?: return .notAdmitted(denial)
    }
}

private func item(_ workshop: Workshop) -> WorkshopItem {
    WorkshopItem(
        id: workshop.id, name: workshop.name, start: workshop.start, seatsHint: workshop.seatsHint,
        requirement: projectRequirement(workshop.requirement)
    )
}

extension Booking {
    fileprivate func summary(_ names: [ParticipantId: String]) -> BookingSummary {
        BookingSummary(
            workshopName: workshop.name, start: workshop.start,
            participantNames: participants.map { names[$0] ?? $0.value }
        )
    }
}
