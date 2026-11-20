package example.reservation.core

import example.eligibility.core.EligibilityAuthority
import example.eligibility.core.EligibilityCertificate
import example.eligibility.core.EligibilityOutcome
import example.eligibility.core.EligibilityStatus
import example.eligibility.core.QualificationKind
import example.eligibility.core.Reverification
import example.eligibility.core.ReverificationFailure
import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.kernel.frozen
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val eligibility: EligibilityAuthority,
    private val clock: Clock,
    private val newRequestId: () -> RequestId,
    private val scope: CoroutineScope,
    private val operationTimeout: Duration,
) : AdmissionSource {
    private val lock = SynchronizedObject()
    private var lastOperation = 0L
    private val state = MutableStateFlow(
        CoreSnapshot(
            draft = Draft(workshop = null, participants = emptyList(), confirmation = Confirmation.Unconfirmed),
            request = null,
            reservations = emptyList(),
        )
    )

    val snapshot: StateFlow<CoreSnapshot> = state.asStateFlow()

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
            draft = Draft(workshop, toggled.frozen(), Confirmation.Unconfirmed),
            request = current.request.withoutFinishedResult(),
        )
        Acceptance.Accepted
    }

    // 現在の参加者と必要資格に対応する確認を、資格 Core へ求める。予約の作成は開始しない。
    fun requestEligibility(): Acceptance = synchronized(lock) {
        val draft = state.value.draft
        val workshop = draft.workshop ?: return@synchronized Acceptance.Rejected(Rejection.UnknownWorkshop)
        val kind = when (val requirement = workshop.requirement) {
            NewBookingRequirement.NotRequired -> return@synchronized Acceptance.Rejected(Rejection.QualificationNotRequired)
            is NewBookingRequirement.Required -> requirement.kind
        }
        if (draft.participants.isEmpty()) return@synchronized Acceptance.Rejected(Rejection.NoParticipants)
        draft.participants
            .filterNot { readinessOf(eligibility.status(it, kind), it, kind) is Readiness.Ready }
            .forEach { eligibility.request(it, kind) }
        Acceptance.Accepted
    }

    fun confirmWithoutQualification(): ConfirmOutcome = synchronized(lock) {
        val current = state.value
        val workshop = current.draft.workshop ?: return@synchronized ConfirmOutcome.NoWorkshop
        when (workshop.requirement) {
            is NewBookingRequirement.Required -> return@synchronized ConfirmOutcome.QualificationRequired
            NewBookingRequirement.NotRequired -> Unit
        }
        if (current.draft.participants.isEmpty()) return@synchronized ConfirmOutcome.EmptyParticipants
        commit(current, ConfirmedDraft.Open(workshop, current.draft.participants))
    }

    fun confirmQualified(): ConfirmOutcome = synchronized(lock) {
        val current = state.value
        val workshop = current.draft.workshop ?: return@synchronized ConfirmOutcome.NoWorkshop
        val kind = when (val requirement = workshop.requirement) {
            NewBookingRequirement.NotRequired -> return@synchronized ConfirmOutcome.QualificationNotRequired
            is NewBookingRequirement.Required -> requirement.kind
        }
        val participants = current.draft.participants
        if (participants.isEmpty()) return@synchronized ConfirmOutcome.EmptyParticipants
        val readiness = participants.map { it to readinessOf(eligibility.status(it, kind), it, kind) }
        val notReady = readiness.filter { it.second is Readiness.NotReady }.map { it.first }
        if (notReady.isNotEmpty()) return@synchronized ConfirmOutcome.EligibilityNotMet(notReady)
        val certificates = readiness.map { (it.second as Readiness.Ready).certificate }
        commit(current, ConfirmedDraft.Qualified(workshop, participants, kind, certificates))
    }

    private fun commit(current: CoreSnapshot, confirmed: ConfirmedDraft): ConfirmOutcome {
        state.value = current.copy(draft = current.draft.copy(confirmation = Confirmation.Confirmed(confirmed)))
        return ConfirmOutcome.Confirmed(confirmed)
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
        val creating = ActiveRequest.Creating(nextOperation(), newRequestId(), ticket.draft, trigger = ticket, resting = request)
        state.value = current.copy(request = creating)
        Step.Accepted { launchCreate(creating) }
    }

    fun cancel(ticket: CancelTicket): Acceptance = transact { current ->
        val record = current.reservations
            .filterIsInstance<ReservationRecord.Reserved>()
            .firstOrNull { it.cancel === ticket }
            ?: return@transact Step.Rejected(current.rejectionForUnmatched(ticket))
        if (current.request.blocksCancel()) return@transact Step.Rejected(Rejection.Busy)
        if (isCancellationClosed(record.booking.workshop)) return@transact Step.Rejected(Rejection.CancellationClosed)
        val cancelling = ActiveRequest.Cancelling(nextOperation(), record, ticket, resting = current.request)
        state.value = current.copy(request = cancelling)
        Step.Accepted { launchCancel(cancelling) }
    }

    // 成立が確認された予約に含まれる参加者だけを有効とする。取消の応答が失われた場合も判断できるよう、
    // 記録だけでなくサービスの現在の状態を照会して確かめる。結果不明の予約は記録が無く、有効にならない。
    override suspend fun admission(reservation: ReservationId, participant: ParticipantId): AdmissionLookup {
        val record = synchronized(lock) { state.value.reservations.firstOrNull { it.reservation == reservation } }
        val reserved = when (record) {
            null -> return AdmissionLookup.Unavailable(AdmissionUnavailableReason.NotReserved)
            is ReservationRecord.Cancelled -> return AdmissionLookup.Unavailable(AdmissionUnavailableReason.Cancelled)
            is ReservationRecord.Reserved -> record
        }
        val admission = reserved.admissions.firstOrNull { it.participant == participant }
            ?: return AdmissionLookup.Unavailable(AdmissionUnavailableReason.NotIncluded)
        return when (observe(UsageObservation.Failed) { port.usage(reservation) }) {
            UsageObservation.Active -> AdmissionLookup.Valid(CurrentAdmission(admission, clock.now()))
            UsageObservation.Cancelled -> AdmissionLookup.Unavailable(AdmissionUnavailableReason.Cancelled)
            UsageObservation.NotFound -> AdmissionLookup.Unavailable(AdmissionUnavailableReason.NotReserved)
            UsageObservation.Failed -> AdmissionLookup.Unconfirmed
        }
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

    // 新規作成を許す判断は、送信と再試行のどちらもここを通る。処理中の操作を確保した後で、資格を全員分まとめて
    // 再確認し、戻ってから受付終了を取り直す。許されなかったときは作成要求を送らない。
    private fun launchCreate(request: ActiveRequest.Creating) {
        scope.launch {
            when (val permission = permitCreate(request.draft)) {
                is CreatePermission.Denied -> settle(request.operation) { it.copy(request = request.deniedBy(permission.denial)) }
                CreatePermission.Granted -> {
                    val observation = observe(CreateObservation.NoResponse) {
                        port.create(request.request, request.draft.workshop.id, request.draft.participants)
                    }
                    settle(request.operation) { it.afterCreate(request, observation) }
                }
            }
        }
    }

    private suspend fun permitCreate(draft: ConfirmedDraft): CreatePermission {
        val certificates = draft.certificates
        var checkedAt: EpochMillis? = null
        if (certificates.isNotEmpty()) {
            val startedAt = clock.now()
            when (val verification = eligibility.verify(certificates)) {
                is Reverification.Verified -> {
                    val finishedAt = clock.now()
                    if (!verification.certificates.coverExactly(draft)) {
                        return CreatePermission.Denied(CreateDenial.EligibilityLost(ReverificationFailure.TargetMismatch))
                    }
                    checkedAt = verification.certificates.commonCheckedAt(startedAt, finishedAt)
                        ?: return CreatePermission.Denied(CreateDenial.EligibilityLost(ReverificationFailure.Unconfirmed))
                }
                is Reverification.Failed ->
                    return CreatePermission.Denied(CreateDenial.EligibilityLost(verification.reason))
            }
        }
        val closedAtCheck = checkedAt?.let { it >= draft.workshop.start } ?: false
        return if (closedAtCheck || isRegistrationClosed(draft.workshop)) {
            CreatePermission.Denied(CreateDenial.RegistrationClosed)
        } else {
            CreatePermission.Granted
        }
    }

    // 今回の再確認で得た証明書だけを使う。全員の判定時刻が一つに揃い、再確認の呼び出し区間に入らない値
    // （過去の証明書の再生や、別の判定の混在）は、資格を確認できなかったものとして扱う。
    private fun List<EligibilityCertificate>.commonCheckedAt(startedAt: EpochMillis, finishedAt: EpochMillis): EpochMillis? {
        val checkedAt = map { it.checkedAt }.distinct().singleOrNull() ?: return null
        return checkedAt.takeIf { it >= startedAt && it <= finishedAt }
    }

    // 再確認で返った証明書が、下書きの参加者と必要資格に過不足なく対応することを確かめる。
    private fun List<EligibilityCertificate>.coverExactly(draft: ConfirmedDraft): Boolean {
        val kind = (draft.workshop.requirement as? NewBookingRequirement.Required)?.kind
        return size == draft.participants.size &&
            map { it.participant }.toSet() == draft.participants.toSet() &&
            all { it.kind == kind }
    }

    private fun ActiveRequest.Creating.deniedBy(denial: CreateDenial): ActiveRequest =
        resting?.let { ActiveRequest.NotCreated(it.retry, denial) }
            ?: when (denial) {
                CreateDenial.RegistrationClosed -> ActiveRequest.RegistrationClosed(draft)
                is CreateDenial.EligibilityLost -> ActiveRequest.EligibilityLost(draft, denial.reason)
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
            is CreateObservation.Created -> withReserved(observation.reservation, request.draft.booking)
            CreateObservation.Full -> copy(request = ActiveRequest.SoldOut(request.draft))
            CreateObservation.NotCreated -> copy(request = ActiveRequest.NotCreated(RetryTicket(request.draft)))
            CreateObservation.NoResponse ->
                copy(request = ActiveRequest.Unknown(StatusCheckTicket(request.request, request.draft)))
        }

    private fun CoreSnapshot.afterLookup(request: ActiveRequest.CheckingStatus, observation: LookupObservation) =
        when (observation) {
            is LookupObservation.Found -> withReserved(observation.reservation, request.ticket.draft.booking)
            LookupObservation.NotCreated -> copy(request = ActiveRequest.NotCreated(RetryTicket(request.ticket.draft)))
            LookupObservation.NotFound, LookupObservation.Failed ->
                copy(request = ActiveRequest.Unknown(StatusCheckTicket(request.ticket.request, request.ticket.draft)))
        }

    private fun CoreSnapshot.afterCancel(request: ActiveRequest.Cancelling, observation: CancelObservation) =
        when (observation) {
            CancelObservation.Cancelled -> copy(
                request = request.resting,
                reservations = reservations.replace(request.reservation) {
                    ReservationRecord.Cancelled(request.reservation, request.booking)
                },
            )
            CancelObservation.Declined -> withCancelFailure(request, CancelFailure.Declined)
            CancelObservation.NoResponse -> withCancelFailure(request, CancelFailure.NoResponse)
        }

    private fun CoreSnapshot.withReserved(reservation: ReservationId, booking: Booking) = copy(
        request = null,
        reservations = (
            reservations + ReservationRecord.Reserved(
                reservation, booking, CancelTicket(reservation), null, admissionsFor(reservation, booking),
            )
        ).frozen(),
    )

    private fun admissionsFor(reservation: ReservationId, booking: Booking): List<ReservationAdmission> =
        booking.participants.map { participant ->
            when (val basis = booking.basis) {
                BookedBasis.WithoutQualification ->
                    ReservationAdmission.Open(reservation, participant, booking.workshop.id, booking.workshop.start)
                is BookedBasis.WithQualification ->
                    ReservationAdmission.Qualified(reservation, participant, booking.workshop.id, booking.workshop.start, basis.kind)
            }
        }

    private fun CoreSnapshot.withCancelFailure(request: ActiveRequest.Cancelling, failure: CancelFailure) = copy(
        request = request.resting,
        reservations = reservations.replace(request.reservation) {
            ReservationRecord.Reserved(
                request.reservation, request.booking, CancelTicket(request.reservation), failure, request.record.admissions,
            )
        },
    )

    private fun List<ReservationRecord>.replace(
        reservation: ReservationId,
        replacement: () -> ReservationRecord,
    ): List<ReservationRecord> = map { if (it.reservation == reservation) replacement() else it }.frozen()

    // 実行中の操作と同じ入力が再び届いたものは Busy、現在の状態に対応しないものは Stale として区別する。
    private fun CoreSnapshot.rejectionForUnmatched(trigger: Any): Rejection {
        val duplicate = when (val running = request) {
            is ActiveRequest.Creating -> running.trigger === trigger
            is ActiveRequest.CheckingStatus -> running.ticket === trigger
            is ActiveRequest.Cancelling -> running.trigger === trigger || running.resting.holdsTicket(trigger)
            is ActiveRequest.Unknown, is ActiveRequest.NotCreated, is ActiveRequest.SoldOut,
            is ActiveRequest.RegistrationClosed, is ActiveRequest.EligibilityLost, null -> false
        }
        return if (duplicate) Rejection.Busy else Rejection.Stale
    }

    private fun ActiveRequest?.holdsTicket(ticket: Any): Boolean = when (this) {
        is ActiveRequest.Unknown -> check === ticket
        is ActiveRequest.NotCreated -> retry === ticket
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling,
        is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed, is ActiveRequest.EligibilityLost, null -> false
    }

    // 期限で拒否した送信は、対象の下書きに結び付けて結果を残す。未成立の再試行券は失わせない。
    private fun ActiveRequest?.withClosedResult(draft: ConfirmedDraft): ActiveRequest? = when (this) {
        null, is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed, is ActiveRequest.EligibilityLost ->
            ActiveRequest.RegistrationClosed(draft)
        is ActiveRequest.NotCreated, is ActiveRequest.Creating, is ActiveRequest.CheckingStatus,
        is ActiveRequest.Cancelling, is ActiveRequest.Unknown -> this
    }

    private fun ActiveRequest?.blocksNewCreate(): Boolean = when (this) {
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling,
        is ActiveRequest.Unknown -> true
        is ActiveRequest.NotCreated, is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed,
        is ActiveRequest.EligibilityLost, null -> false
    }

    // 外部要求は同時に一つだけ。取消は、実行中の要求がなければ、未解決の結果を保持したまま受け付ける。
    private fun ActiveRequest?.blocksCancel(): Boolean = when (this) {
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling -> true
        is ActiveRequest.Unknown, is ActiveRequest.NotCreated, is ActiveRequest.SoldOut,
        is ActiveRequest.RegistrationClosed, is ActiveRequest.EligibilityLost, null -> false
    }

    private fun ActiveRequest?.withoutFinishedResult(): ActiveRequest? = when (this) {
        is ActiveRequest.SoldOut, is ActiveRequest.RegistrationClosed, is ActiveRequest.EligibilityLost -> null
        is ActiveRequest.Creating, is ActiveRequest.CheckingStatus, is ActiveRequest.Cancelling,
        is ActiveRequest.Unknown, is ActiveRequest.NotCreated, null -> this
    }

    // 予約へ進めるかの判断。資格の結果が増えたときは、ここで進めてよいかを個別に決める。
    private fun readinessOf(status: EligibilityStatus, participant: ParticipantId, kind: QualificationKind): Readiness =
        when (status) {
            EligibilityStatus.NotChecked, EligibilityStatus.Checking -> Readiness.NotReady
            is EligibilityStatus.Settled -> readinessOfOutcome(status.outcome, participant, kind)
        }

    // 別人や別の種別の確認結果は、参加者と種別が一致しなければ受け取らない。
    private fun readinessOfOutcome(outcome: EligibilityOutcome, participant: ParticipantId, kind: QualificationKind): Readiness =
        when (outcome) {
            is EligibilityOutcome.Eligible ->
                if (outcome.certificate.participant == participant && outcome.certificate.kind == kind) {
                    Readiness.Ready(outcome.certificate)
                } else {
                    Readiness.NotReady
                }
            EligibilityOutcome.NotHeld, EligibilityOutcome.Expired, EligibilityOutcome.Unknown -> Readiness.NotReady
        }

    private fun isRegistrationClosed(workshop: Workshop): Boolean = clock.now() >= workshop.start

    private fun isCancellationClosed(workshop: Workshop): Boolean =
        clock.now().value >= workshop.start.value - CANCELLATION_WINDOW_MILLIS

    private fun nextOperation(): OperationId = OperationId(++lastOperation)

    private sealed interface Step {
        class Rejected(val reason: Rejection) : Step
        class Accepted(val start: () -> Unit) : Step
    }

    private sealed interface Readiness {
        class Ready(val certificate: EligibilityCertificate) : Readiness
        data object NotReady : Readiness
    }

    private sealed interface CreatePermission {
        data object Granted : CreatePermission
        class Denied(val denial: CreateDenial) : CreatePermission
    }
}

fun createReservationCore(
    catalog: Catalog,
    port: ReservationPort,
    eligibility: EligibilityAuthority,
    clock: Clock,
    scope: CoroutineScope,
    newRequestId: () -> RequestId,
    operationTimeout: Duration,
): ReservationCore = ReservationCore(catalog, port, eligibility, clock, newRequestId, scope, operationTimeout)
