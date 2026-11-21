import Kernel

extension CoreState {
    func rejection(forUnmatched trigger: ObjectIdentifier) -> Rejection {
        let duplicate: Bool
        switch request {
        case .creating(let job)?: duplicate = job.trigger == trigger
        case .checkingStatus(_, let ticket)?: duplicate = ObjectIdentifier(ticket) == trigger
        case .cancelling(let job)?: duplicate = job.trigger == trigger || job.resting?.holdsTicket(trigger) == true
        case .unknown?, .notCreated?, .soldOut?, .registrationClosed?, .eligibilityLost?, nil: duplicate = false
        }
        return duplicate ? .busy : .stale
    }

    mutating func apply(_ observation: CreateObservation, to job: CreateJob) {
        switch observation {
        case .created(let reservation): recordReserved(reservation, job.draft.booking)
        case .full: request = .soldOut(job.draft)
        case .notCreated: request = .notCreated(RestingNotCreated(retry: RetryTicket(draft: job.draft), lastDenial: nil))
        case .noResponse: request = .unknown(StatusCheckTicket(request: job.request, draft: job.draft))
        }
    }

    mutating func apply(_ observation: LookupObservation, to ticket: StatusCheckTicket) {
        switch observation {
        case .found(let reservation):
            recordReserved(reservation, ticket.draft.booking)
        case .notCreated:
            request = .notCreated(RestingNotCreated(retry: RetryTicket(draft: ticket.draft), lastDenial: nil))
        case .notFound, .failed:
            request = .unknown(StatusCheckTicket(request: ticket.request, draft: ticket.draft))
        }
    }

    mutating func apply(_ observation: CancelObservation, to job: CancelJob) {
        let reservation = job.record.reservation
        request = job.resting
        switch observation {
        case .cancelled:
            replaceRecord(reservation, with: .cancelled(reservation: reservation, booking: job.record.booking))
        case .declined:
            replaceRecord(reservation, with: .reserved(job.record.withCancelFailure(.declined)))
        case .noResponse:
            replaceRecord(reservation, with: .reserved(job.record.withCancelFailure(.noResponse)))
        }
    }

    private mutating func recordReserved(_ reservation: ReservationId, _ booking: Booking) {
        request = nil
        let admissions = booking.participants.map {
            ReservationAdmission(
                reservation: reservation, participant: $0, session: booking.workshop.id,
                start: booking.workshop.start, basis: booking.basis
            )
        }
        reservations.append(.reserved(ReservedRecord(
            reservation: reservation, booking: booking, cancel: CancelTicket(reservation: reservation),
            lastCancelFailure: nil, admissions: admissions
        )))
    }

    private mutating func replaceRecord(_ reservation: ReservationId, with replacement: ReservationRecord) {
        reservations = reservations.map { $0.reservation == reservation ? replacement : $0 }
    }
}

extension ReservedRecord {
    fileprivate func withCancelFailure(_ failure: CancelFailure) -> ReservedRecord {
        ReservedRecord(
            reservation: reservation, booking: booking, cancel: CancelTicket(reservation: reservation),
            lastCancelFailure: failure, admissions: admissions
        )
    }
}
