package dca.core

class ReservationCore(
    private val workshop: Workshop,
    private val port: ReservationPort,
    private val clock: Clock,
    private val newRequestId: () -> RequestId,
) {
    private sealed interface Phase {
        data object Editing : Phase
        class Confirmed(val draft: ConfirmedDraft) : Phase
        class Settled(val outcome: Outcome) : Phase
    }

    private var phase: Phase = Phase.Editing

    val current: Outcome? get() = (phase as? Phase.Settled)?.outcome

    fun confirm(participants: List<ParticipantId>): ConfirmOutcome {
        // 単一の予約試行を表す。送信後の結果は、確認し直しではなく照会・再試行の前提だけで進める。
        if (phase is Phase.Settled) return ConfirmOutcome.AttemptAlreadyStarted
        if (participants.isEmpty()) return ConfirmOutcome.EmptyParticipants
        if (participants.toSet().size != participants.size) return ConfirmOutcome.DuplicateParticipants
        val draft = ConfirmedDraft(workshop, participants.toList())
        phase = Phase.Confirmed(draft)
        return ConfirmOutcome.Confirmed(draft)
    }

    fun submit(confirmed: ConfirmedDraft): Outcome {
        val p = phase
        if (p !is Phase.Confirmed || p.draft !== confirmed) return Outcome.Stale
        if (isClosed()) return settle(Outcome.RegistrationClosed)
        return settle(send(confirmed))
    }

    fun checkStatus(ticket: StatusCheckTicket): Outcome {
        val known = current
        if (known !is Outcome.Unknown || known.check !== ticket) return Outcome.Stale
        return when (val observation = port.lookup(ticket.request)) {
            is LookupObservation.Found -> settle(Outcome.Reserved(observation.reservation))
            LookupObservation.NotCreated -> settle(Outcome.NotCreated(RetryTicket(ticket.draft)))
            LookupObservation.NotFound -> known
            LookupObservation.Failed -> known
        }
    }

    fun retry(ticket: RetryTicket): Outcome {
        val known = current
        if (known !is Outcome.NotCreated || known.retry !== ticket) return Outcome.Stale
        if (isClosed()) return Outcome.RetryClosed(known)
        return settle(send(ticket.draft))
    }

    private fun isClosed(): Boolean = clock.now() >= workshop.start

    // create の前に新しい RequestId を発行する。応答が失われても、照会が同じ ID を使える。
    private fun send(draft: ConfirmedDraft): Outcome {
        val request = newRequestId()
        return when (val observation = port.create(request, workshop.id, draft.participants)) {
            is CreateObservation.Created -> Outcome.Reserved(observation.reservation)
            CreateObservation.Full -> Outcome.SoldOut
            CreateObservation.NotCreated -> Outcome.NotCreated(RetryTicket(draft))
            CreateObservation.NoResponse -> Outcome.Unknown(StatusCheckTicket(request, draft))
        }
    }

    private fun settle(outcome: Outcome): Outcome {
        phase = Phase.Settled(outcome)
        return outcome
    }
}
