package example.reservation.ui

import example.checkin.core.CheckInAttemptId
import example.checkin.core.CheckInCreateObservation
import example.checkin.core.CheckInLookupObservation
import example.checkin.core.CheckInPort
import example.checkin.core.createCheckInCore
import example.eligibility.core.EligibilityPort
import example.eligibility.core.QualificationKind
import example.eligibility.core.QualificationObservation
import example.eligibility.core.createEligibilityCore
import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.CancelObservation
import example.reservation.core.Catalog
import example.reservation.core.ConfirmOutcome
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.NewBookingRequirement
import example.reservation.core.Participant
import example.reservation.core.RequestId
import example.reservation.core.ReservationId
import example.reservation.core.ReservationPort
import example.reservation.core.UsageObservation
import example.reservation.core.Workshop
import example.reservation.core.createReservationCore
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal const val ACTION_MINUTE = 60_000L
internal const val ACTION_HOUR = 60 * ACTION_MINUTE
internal const val ACTION_START = 100 * ACTION_HOUR

private val actionOpen = Workshop(SessionId("open"), "通常回", EpochMillis(ACTION_START), 4, NewBookingRequirement.NotRequired)
private val actionAlice = ParticipantId("alice")
private val actionCatalog = Catalog(listOf(actionOpen), listOf(Participant(actionAlice, "アリス")))

internal class ScriptedReservations : ReservationPort {
    val createScript = ArrayDeque<CreateObservation>()
    val lookupScript = ArrayDeque<LookupObservation>()
    val createCalls = mutableListOf<RequestId>()
    val lookupCalls = mutableListOf<RequestId>()

    override suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
        createCalls += request
        return createScript.removeFirstOrNull() ?: CreateObservation.Created(ReservationId("r-${createCalls.size}"))
    }

    override suspend fun lookup(request: RequestId): LookupObservation {
        lookupCalls += request
        return lookupScript.removeFirstOrNull() ?: LookupObservation.NotFound
    }

    override suspend fun cancel(reservation: ReservationId): CancelObservation = CancelObservation.Cancelled

    override suspend fun usage(reservation: ReservationId): UsageObservation = UsageObservation.Active
}

private object NoQualifications : EligibilityPort {
    override suspend fun fetch(participant: ParticipantId, kind: QualificationKind): QualificationObservation =
        QualificationObservation.NotHeld
}

private object NoCheckIns : CheckInPort {
    override suspend fun create(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId): CheckInCreateObservation =
        CheckInCreateObservation.Recorded

    override suspend fun lookup(attempt: CheckInAttemptId): CheckInLookupObservation = CheckInLookupObservation.Found
}

// 一つの Core を複数の ViewModel（実行先）が共有できるようにした、操作券の試験用の組み立て。
@OptIn(ExperimentalCoroutinesApi::class)
internal class ActionSession(private val scope: TestScope) {
    var now: Long = ACTION_START - 2 * ACTION_HOUR
    val port = ScriptedReservations()
    private val coroutines = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    private val clock = Clock { EpochMillis(now) }
    private var requests = 0
    private var attempts = 0
    private val eligibility = createEligibilityCore(NoQualifications, clock, coroutines, 30.seconds)
    private val core = createReservationCore(actionCatalog, port, eligibility, clock, coroutines, { RequestId("R${++requests}") }, 30.seconds)
    private val checkIn = createCheckInCore(core, NoCheckIns, clock, coroutines, { CheckInAttemptId("a-${++attempts}") }, 30.seconds)

    fun endpoint(): ReservationViewModel = ReservationViewModel(core, eligibility, checkIn, actionCatalog)

    fun settle() = scope.testScheduler.runCurrent()

    val status: StatusState
        get() = project(core.snapshot.value, eligibility.snapshot.value, checkIn.snapshot.value, actionCatalog).status

    val request: RequestCard
        get() = checkNotNull(status.request) { "予約の要求が残っていません" }

    val unknown: RequestCard.Unknown
        get() = request.also { assertIs<RequestCard.Unknown>(it) } as RequestCard.Unknown

    val notCreated: RequestCard.NotCreated
        get() = request.also { assertIs<RequestCard.NotCreated>(it) } as RequestCard.NotCreated

    fun submit(endpoint: ReservationViewModel, createdAs: CreateObservation) {
        port.createScript += createdAs
        endpoint.selectWorkshop(actionOpen.id)
        endpoint.toggleParticipant(actionAlice)
        val confirmed = endpoint.confirm()
        assertIs<ConfirmOutcome.Confirmed>(confirmed)
        endpoint.submit(confirmed.draft)
        settle()
    }

    fun lookUp(endpoint: ReservationViewModel, answer: LookupObservation) {
        port.lookupScript += answer
        val card = request
        assertIs<RequestCard.Unknown>(card)
        endpoint.checkStatus(card.check)
        settle()
    }

    fun retry(endpoint: ReservationViewModel, createdAs: CreateObservation) {
        port.createScript += createdAs
        val card = request
        assertIs<RequestCard.NotCreated>(card)
        endpoint.retry(card.retry)
        settle()
    }
}
