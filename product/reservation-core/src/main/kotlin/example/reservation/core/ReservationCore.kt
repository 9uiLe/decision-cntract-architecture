package example.reservation.core

import example.reservation.ports.CancelObservation
import example.reservation.ports.CreateObservation
import example.reservation.ports.LookupObservation
import example.reservation.ports.ParticipantId
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationId
import example.reservation.ports.ReservationPort
import example.reservation.ports.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private const val CANCELLATION_WINDOW_MILLIS = 24L * 60 * 60 * 1000

// 外部要求は呼び出し元ではなく scope（Application の寿命）で実行する。画面や ViewModel が破棄されても
// 要求を途中で捨てず、結果は snapshot にだけ反映する。操作の受付は排他区間で状態を進める短い処理にとどめ、
// Port の呼び出しは排他の外で行う。
class ReservationCore internal constructor(
    private val catalog: Catalog,
    private val port: ReservationPort,
    private val clock: Clock,
    private val newRequestId: () -> RequestId,
    private val scope: CoroutineScope,
    private val operationTimeout: Duration,
) {
    private val lock = Any()
    private var lastOperation = 0L
    private val state = MutableStateFlow(
        CoreSnapshot(
            draft = Draft(workshop = null, participants = emptyList(), confirmation = Confirmation.Unconfirmed),
            request = null,
            reservations = emptyList(),
        )
    )

    val snapshot: StateFlow<CoreSnapshot> get() = state

    fun selectWorkshop(session: SessionId): Acceptance = synchronized(lock) {
        val workshop = catalog.workshops.firstOrNull { it.id == session }
            ?: return@synchronized Acceptance.Rejected(Rejection.UnknownWorkshop)
        val current = state.value
        if (current.draft.workshop?.id == session) return@synchronized Acceptance.Accepted
        state.value = current.copy(
            draft = Draft(workshop, emptyList(), Confirmation.Unconfirmed),
            request = current.request.withoutFinishedResult(),
        )
        Acceptance.Accepted
    }

    fun toggleParticipant(participant: ParticipantId): Acceptance = synchronized(lock) {
        val current = state.value
        val workshop = current.draft.workshop
            ?: return@synchronized Acceptance.Rejected(Rejection.UnknownWorkshop)
        if (catalog.participants.none { it.id == participant }) {
            return@synchronized Acceptance.Rejected(Rejection.UnknownParticipant)
        }
        val toggled = if (participant in current.draft.participants) {
            current.draft.participants - participant
        } else {
            current.draft.participants + participant
        }
        state.value = current.copy(
            draft = Draft(workshop, toggled, Confirmation.Unconfirmed),
            request = current.request.withoutFinishedResult(),
        )
        Acceptance.Accepted
    }

    fun confirm(): ConfirmOutcome = synchronized(lock) {
        val current = state.value
        val workshop = current.draft.workshop ?: return@synchronized ConfirmOutcome.NoWorkshop
        if (current.draft.participants.isEmpty()) return@synchronized ConfirmOutcome.EmptyParticipants
        val confirmed = ConfirmedDraft(workshop, current.draft.participants)
        state.value = current.copy(draft = current.draft.copy(confirmation = Confirmation.Confirmed(confirmed)))
        ConfirmOutcome.Confirmed(confirmed)
    }

    fun submit(confirmed: ConfirmedDraft): Acceptance = transact { current ->
        val confirmation = current.draft.confirmation
        if (confirmation !is Confirmation.Confirmed || confirmation.draft !== confirmed) {
            return@transact Step.Rejected(current.rejectionForUnmatched(confirmed))
        }
        if (current.request.blocksNewCreate()) return@transact Step.Rejected(Rejection.Busy)
        if (isRegistrationClosed(confirmed.workshop)) {
            state.value = current.copy(request = current.request.withClosedResult(confirmed))
            return@transact Step.Rejected(Rejection.RegistrationClosed)
        }
        val creating = ActiveRequest.Creating(nextOperation(), newRequestId(), confirmed, trigger = confirmed)
        state.value = current.copy(
            draft = current.draft.copy(confirmation = Confirmation.Unconfirmed),
            request = creating,
        )
        Step.Accepted { launchCreate(creating) }
    }

    fun checkStatus(ticket: StatusCheckTicket): Acceptance = transact { current ->
        val request = current.request
        if (request !is ActiveRequest.Unknown || request.check !== ticket) {
            return@transact Step.Rejected(current.rejectionForUnmatched(ticket))
        }
        val checking = ActiveRequest.CheckingStatus(nextOperation(), ticket)
        state.value = current.copy(request = checking)
        Step.Accepted { launchLookup(checking) }
    }

    fun retry(ticket: RetryTicket): Acceptance = transact { current ->
        val request = current.request
        if (request !is ActiveRequest.NotCreated || request.retry !== ticket) {
            return@transact Step.Rejected(current.rejectionForUnmatched(ticket))
        }
        if (isRegistrationClosed(ticket.draft.workshop)) return@transact Step.Rejected(Rejection.RegistrationClosed)
        val creating = ActiveRequest.Creating(nextOperation(), newRequestId(), ticket.draft, trigger = ticket)
        state.value = current.copy(request = creating)
        Step.Accepted { launchCreate(creating) }
    }

    fun cancel(ticket: CancelTicket): Acceptance = transact { current ->
        val record = current.reservations
            .filterIsInstance<ReservationRecord.Reserved>()
            .firstOrNull { it.cancel === ticket }
            ?: return@transact Step.Rejected(current.rejectionForUnmatched(ticket))
        if (current.request.blocksCancel()) return@transact Step.Rejected(Rejection.Busy)
        if (isCancellationClosed(record.draft.workshop)) return@transact Step.Rejected(Rejection.CancellationClosed)
        val cancelling = ActiveRequest.Cancelling(
            nextOperation(), record.reservation, record.draft, ticket, resting = current.request,
        )
        state.value = current.copy(request = cancelling)
        Step.Accepted { launchCancel(cancelling) }
    }

    private inline fun transact(decide: (CoreSnapshot) -> Step): Acceptance {
        val step = synchronized(lock) { decide(state.value) }
        return when (step) {
            is Step.Rejected -> Acceptance.Rejected(step.reason)
            is Step.Accepted -> {
                step.start()
                Acceptance.Accepted
            }
        }
    }

    private fun launchCreate(request: ActiveRequest.Creating) {
        scope.launch {
            val observation = observe(CreateObservation.NoResponse) {
                port.create(request.request, request.draft.workshop.id, request.draft.participants)
            }
            settle(request.operation) { it.afterCreate(request, observation) }
        }
    }

    private fun launchLookup(request: ActiveRequest.CheckingStatus) {
        scope.launch {
            val observation = observe(LookupObservation.Failed) { port.lookup(request.ticket.request) }
            settle(request.operation) { it.afterLookup(request, observation) }
        }
    }

    private fun launchCancel(request: ActiveRequest.Cancelling) {
        scope.launch {
            val observation = observe(CancelObservation.NoResponse) { port.cancel(request.reservation) }
            settle(request.operation) { it.afterCancel(request, observation) }
        }
    }

    // 時間切れ・例外・Port 側のキャンセルは、結果を確認できなかったこととして扱い、処理中のまま残さない。
    // 外側の scope が止められた場合だけキャンセルを伝える。
    private suspend fun <T> observe(unconfirmed: T, call: suspend () -> T): T = try {
        withTimeoutOrNull(operationTimeout) { call() } ?: unconfirmed
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        unconfirmed
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        unconfirmed
    }

    // 応答は、その操作がいまも現在の要求であるときにだけ反映する。古い応答は捨てる。
    private fun settle(operation: OperationId, next: (CoreSnapshot) -> CoreSnapshot) {
        synchronized(lock) {
            val current = state.value
            if (current.request?.operation != operation) return
            state.value = next(current)
        }
    }

    private fun CoreSnapshot.afterCreate(request: ActiveRequest.Creating, observation: CreateObservation) =
        when (observation) {
            is CreateObservation.Created -> withReserved(observation.reservation, request.draft)
            CreateObservation.Full -> copy(request = ActiveRequest.SoldOut(request.draft))
            CreateObservation.NotCreated -> copy(request = ActiveRequest.NotCreated(RetryTicket(request.draft)))
            CreateObservation.NoResponse ->
                copy(request = ActiveRequest.Unknown(StatusCheckTicket(request.request, request.draft)))
        }

    private fun CoreSnapshot.afterLookup(request: ActiveRequest.CheckingStatus, observation: LookupObservation) =
        when (observation) {
            is LookupObservation.Found -> withReserved(observation.reservation, request.draft)
            LookupObservation.NotCreated -> copy(request = ActiveRequest.NotCreated(RetryTicket(request.draft)))
            LookupObservation.NotFound, LookupObservation.Failed ->
                copy(request = ActiveRequest.Unknown(StatusCheckTicket(request.ticket.request, request.draft)))
        }

    private fun CoreSnapshot.afterCancel(request: ActiveRequest.Cancelling, observation: CancelObservation) =
        when (observation) {
            CancelObservation.Cancelled -> copy(
                request = request.resting,
                reservations = reservations.replace(request.reservation) {
                    ReservationRecord.Cancelled(request.reservation, request.draft)
                },
            )
            CancelObservation.Declined -> withCancelFailure(request, CancelFailure.Declined)
            CancelObservation.NoResponse -> withCancelFailure(request, CancelFailure.NoResponse)
        }

    private fun CoreSnapshot.withReserved(reservation: ReservationId, draft: ConfirmedDraft) = copy(
        request = null,
        reservations = reservations + ReservationRecord.Reserved(reservation, draft, CancelTicket(reservation), null),
    )

    private fun CoreSnapshot.withCancelFailure(request: ActiveRequest.Cancelling, failure: CancelFailure) = copy(
        request = request.resting,
        reservations = reservations.replace(request.reservation) {
            ReservationRecord.Reserved(request.reservation, request.draft, CancelTicket(request.reservation), failure)
        },
    )

    private fun List<ReservationRecord>.replace(
        reservation: ReservationId,
        replacement: () -> ReservationRecord,
    ): List<ReservationRecord> = map { if (it.reservation == reservation) replacement() else it }

    // 実行中の操作と同じ入力が再び届いたものは Busy、現在の状態に対応しないものは Stale として区別する。
    private fun CoreSnapshot.rejectionForUnmatched(trigger: Any): Rejection {
        val duplicate = when (val running = request) {
            is ActiveRequest.Creating -> running.trigger === trigger
            is ActiveRequest.CheckingStatus -> running.ticket === trigger
            is ActiveRequest.Cancelling -> running.trigger === trigger || running.resting.holdsTicket(trigger)
            is ActiveRequest.Unknown, is ActiveRequest.NotCreated, is ActiveRequest.SoldOut,
            is ActiveRequest.RegistrationClosed, null -> false
        }
        return if (duplicate) Rejection.Busy else Rejection.Stale
    }

    private fun ActiveRequest?.holdsTicket(ticket: Any): Boolean = when (this) {
        is ActiveRequest.Unknown -> check === ticket
        is ActiveRequest.NotCreated -> retry === ticket
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling,
        is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed, null -> false
    }

    // 期限で拒否した送信は、対象の下書きに結び付けて結果を残す。未成立の再試行券は失わせない。
    private fun ActiveRequest?.withClosedResult(draft: ConfirmedDraft): ActiveRequest? = when (this) {
        null, is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed -> ActiveRequest.RegistrationClosed(draft)
        is ActiveRequest.NotCreated, is ActiveRequest.Creating, is ActiveRequest.CheckingStatus,
        is ActiveRequest.Cancelling, is ActiveRequest.Unknown -> this
    }

    private fun ActiveRequest?.blocksNewCreate(): Boolean = when (this) {
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling,
        is ActiveRequest.Unknown -> true
        is ActiveRequest.NotCreated, is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed, null -> false
    }

    // 外部要求は同時に一つだけ。取消は、実行中の要求がなければ、未解決の結果を保持したまま受け付ける。
    private fun ActiveRequest?.blocksCancel(): Boolean = when (this) {
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling -> true
        is ActiveRequest.Unknown, is ActiveRequest.NotCreated, is ActiveRequest.SoldOut,
        is ActiveRequest.RegistrationClosed, null -> false
    }

    private fun ActiveRequest?.withoutFinishedResult(): ActiveRequest? = when (this) {
        is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed -> null
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling,
        is ActiveRequest.Unknown, is ActiveRequest.NotCreated, null -> this
    }

    private fun isRegistrationClosed(workshop: Workshop): Boolean = clock.now() >= workshop.start

    private fun isCancellationClosed(workshop: Workshop): Boolean =
        clock.now().value >= workshop.start.value - CANCELLATION_WINDOW_MILLIS

    private fun nextOperation(): OperationId = OperationId(++lastOperation)

    private sealed interface Step {
        class Rejected(val reason: Rejection) : Step
        class Accepted(val start: () -> Unit) : Step
    }
}

fun createReservationCore(
    catalog: Catalog,
    port: ReservationPort,
    clock: Clock,
    scope: CoroutineScope,
    newRequestId: () -> RequestId,
    operationTimeout: Duration,
): ReservationCore = ReservationCore(catalog, port, clock, newRequestId, scope, operationTimeout)
