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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val START = 100 * HOUR
private val advancedKind = QualificationKind("pottery-advanced", "上級ろくろ修了")
private val alice = ParticipantId("alice")
private val bob = ParticipantId("bob")
private val open = Workshop(SessionId("open"), "通常回", EpochMillis(START), 4, NewBookingRequirement.NotRequired)
private val advanced = Workshop(SessionId("adv"), "上級回", EpochMillis(START), 4, NewBookingRequirement.Required(advancedKind))
private val catalog = Catalog(listOf(open, advanced), listOf(Participant(alice, "アリス"), Participant(bob, "ボブ")))

private class Qualifications : EligibilityPort {
    val holdings = mutableMapOf<ParticipantId, EpochMillis>()

    override suspend fun fetch(participant: ParticipantId, kind: QualificationKind): QualificationObservation =
        holdings[participant]?.let { QualificationObservation.Held(it) } ?: QualificationObservation.NotHeld
}

private class Reservations : ReservationPort {
    var creates = 0

    override suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
        creates++
        return CreateObservation.Created(ReservationId("r-$creates"))
    }

    override suspend fun lookup(request: RequestId): LookupObservation = LookupObservation.NotFound

    override suspend fun cancel(reservation: ReservationId): CancelObservation = CancelObservation.Cancelled

    override suspend fun usage(reservation: ReservationId): UsageObservation = UsageObservation.Active
}

private class CheckIns : CheckInPort {
    var creates = 0

    override suspend fun create(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId): CheckInCreateObservation {
        creates++
        return CheckInCreateObservation.Recorded
    }

    override suspend fun lookup(attempt: CheckInAttemptId): CheckInLookupObservation = CheckInLookupObservation.Found
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Screen(private val scope: TestScope, var now: Long = START - 2 * HOUR) {
    val qualifications = Qualifications()
    val reservations = Reservations()
    val checkIns = CheckIns()
    private val coroutines = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    private val clock = Clock { EpochMillis(now) }
    private var attempt = 0
    private val eligibility = createEligibilityCore(qualifications, clock, coroutines, 30.seconds)
    private val core = createReservationCore(catalog, reservations, eligibility, clock, coroutines, { RequestId("q") }, 30.seconds)
    private val checkIn = createCheckInCore(core, checkIns, clock, coroutines, { CheckInAttemptId("a-${++attempt}") }, 30.seconds)
    val viewModel = ReservationViewModel(core, eligibility, checkIn, catalog)

    val shown: ReservationScreenState
        get() = project(core.snapshot.value, eligibility.snapshot.value, checkIn.snapshot.value, catalog)

    fun settle() = scope.runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionTest {
    @Test
    fun `要資格回は参加者ごとの資格の状況を表し、全員がそろうまで確認へ進めない`() = runTest {
        val s = Screen(this)
        s.qualifications.holdings[alice] = EpochMillis(START + HOUR)
        s.viewModel.selectWorkshop(advanced.id)
        s.viewModel.toggleParticipant(alice)
        s.viewModel.toggleParticipant(bob)
        assertEquals(listOf(QualificationBadge.NotChecked, QualificationBadge.NotChecked), s.shown.details.participants.map { it.qualification })
        assertEquals(false, s.shown.details.canConfirm)
        assertEquals(true, s.shown.details.canRequestEligibility)

        s.viewModel.requestEligibility()
        s.settle()
        assertEquals(listOf(QualificationBadge.Valid, QualificationBadge.NotHeld), s.shown.details.participants.map { it.qualification })
        assertEquals(false, s.shown.details.canConfirm)

        s.qualifications.holdings[bob] = EpochMillis(START + HOUR)
        s.viewModel.requestEligibility()
        s.settle()
        assertEquals(true, s.shown.details.canConfirm)
    }

    @Test
    fun `通常回は資格の表示も確認の導線も出さない`() = runTest {
        val s = Screen(this)
        s.viewModel.selectWorkshop(open.id)
        s.viewModel.toggleParticipant(alice)
        assertEquals(listOf(null, null), s.shown.details.participants.map { it.qualification })
        assertEquals(RequirementView.NotRequired, s.shown.details.workshop?.requirement)
        assertEquals(false, s.shown.details.canRequestEligibility)
        assertEquals(true, s.shown.details.canConfirm)
    }

    @Test
    fun `画面を経由せず確認を押しても、条件を満たさなければ Core が確認済みにせず、通知を出す`() = runTest {
        val s = Screen(this)
        s.viewModel.selectWorkshop(advanced.id)
        s.viewModel.toggleParticipant(alice)
        assertIs<ConfirmOutcome.EligibilityNotMet>(s.viewModel.confirm())
        assertNotNull(s.viewModel.notice.value)
        assertEquals(DetailsStage.Editing, s.shown.details.stage)
        assertEquals(0, s.reservations.creates)
    }

    @Test
    fun `予約詳細は参加者ごとの受付状態を表し、受付後も他の参加者は未受付のまま`() = runTest {
        val s = Screen(this)
        s.viewModel.selectWorkshop(open.id)
        s.viewModel.toggleParticipant(alice)
        s.viewModel.toggleParticipant(bob)
        val confirmed = assertIs<ConfirmOutcome.Confirmed>(s.viewModel.confirm())
        s.viewModel.submit(confirmed.draft)
        s.settle()
        s.now = START - 10 * MINUTE
        val rows = assertIs<ReservationCard.Reserved>(s.shown.status.reservations.single()).participants
        assertEquals(listOf(CheckInView.NotCheckedIn, CheckInView.NotCheckedIn), rows.map { it.view })

        s.viewModel.checkInParticipant(rows.first().admission)
        s.settle()
        val after = assertIs<ReservationCard.Reserved>(s.shown.status.reservations.single()).participants
        assertEquals(listOf(CheckInView.CheckedIn, CheckInView.NotCheckedIn), after.map { it.view })
        assertEquals(1, s.checkIns.creates)
    }

    @Test
    fun `受付できない操作は通知として伝え、画面の再操作でも受付作成要求は重ならない`() = runTest {
        val s = Screen(this)
        s.viewModel.selectWorkshop(open.id)
        s.viewModel.toggleParticipant(alice)
        s.viewModel.submit(assertIs<ConfirmOutcome.Confirmed>(s.viewModel.confirm()).draft)
        s.settle()
        val admission = assertIs<ReservationCard.Reserved>(s.shown.status.reservations.single()).participants.single().admission
        s.viewModel.checkInParticipant(admission)
        assertEquals(checkInRejectionMessage(example.checkin.core.CheckInRejection.NotYetOpen), s.viewModel.notice.value?.message)
        s.now = START - 10 * MINUTE
        repeat(3) { s.viewModel.checkInParticipant(admission) }
        s.settle()
        assertEquals(1, s.checkIns.creates)
    }

    @Test
    fun `すべての状態に利用者向けの文言があり、実装上の語を含まない`() {
        val labels = listOf(
            QualificationBadge.entries.map(::qualificationLabel),
            example.checkin.core.ProcessingStage.entries.map(::processingLabel),
            example.reservation.core.Rejection.entries.map(::rejectionMessage),
            example.reservation.core.AdmissionUnavailableReason.entries.map(::unavailableMessage),
            example.checkin.core.CheckInRejection.entries.map(::checkInRejectionMessage),
            example.eligibility.core.ReverificationFailure.entries.map(::eligibilityLostMessage),
        ).flatten()
        assertEquals(emptyList(), labels.filter { Regex("Core|Port|Ticket|Admission|Certificate|Exception").containsMatchIn(it) })
    }
}
