package example.checkin.core

import example.eligibility.core.EligibilityPort
import example.eligibility.core.QualificationKind
import example.eligibility.core.QualificationObservation
import example.eligibility.core.createEligibilityCore
import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.AdmissionLookup
import example.reservation.core.AdmissionSource
import example.reservation.core.AdmissionUnavailableReason
import example.reservation.core.CancelObservation
import example.reservation.core.Catalog
import example.reservation.core.ConfirmOutcome
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.NewBookingRequirement
import example.reservation.core.Participant
import example.reservation.core.RequestId
import example.reservation.core.ReservationAdmission
import example.reservation.core.ReservationId
import example.reservation.core.ReservationPort
import example.reservation.core.ReservationRecord
import example.reservation.core.UsageObservation
import example.reservation.core.Workshop
import example.reservation.core.createReservationCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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
private val session = SessionId("open")
private val alice = ParticipantId("alice")
private val bob = ParticipantId("bob")
private val carol = ParticipantId("carol")
private val workshop = Workshop(session, "通常回", EpochMillis(START), 4, NewBookingRequirement.NotRequired)
private val catalog = Catalog(listOf(workshop), listOf(Participant(alice, "A"), Participant(bob, "B"), Participant(carol, "C")))

private class ReservationService : ReservationPort {
    var usage: UsageObservation = UsageObservation.Active
    var cancel: CancelObservation = CancelObservation.Cancelled
    var creates = 0

    override suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
        creates++
        return CreateObservation.Created(ReservationId("r-$creates"))
    }

    override suspend fun lookup(request: RequestId): LookupObservation = LookupObservation.NotFound

    override suspend fun cancel(reservation: ReservationId): CancelObservation = cancel

    override suspend fun usage(reservation: ReservationId): UsageObservation = usage
}

private class CheckInService : CheckInPort {
    val creates = mutableListOf<CheckInAttemptId>()
    val lookups = mutableListOf<CheckInAttemptId>()
    var onCreate: suspend (CheckInAttemptId) -> CheckInCreateObservation = { CheckInCreateObservation.Recorded }
    var onLookup: suspend (CheckInAttemptId) -> CheckInLookupObservation = { CheckInLookupObservation.Found }

    override suspend fun create(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId): CheckInCreateObservation {
        creates += attempt
        return onCreate(attempt)
    }

    override suspend fun lookup(attempt: CheckInAttemptId): CheckInLookupObservation {
        lookups += attempt
        return onLookup(attempt)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class World(private val scope: TestScope, var now: Long = START - 72 * HOUR) {
    val reservations = ReservationService()
    val checkIns = CheckInService()
    private val coroutines = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    private val clock = Clock { EpochMillis(now) }
    val reservation = createReservationCore(
        catalog,
        reservations,
        createEligibilityCore(
            object : EligibilityPort {
                override suspend fun fetch(participant: ParticipantId, kind: QualificationKind) = QualificationObservation.Unavailable
            },
            clock, coroutines, 30.seconds,
        ),
        clock, coroutines, { RequestId("q") }, 30.seconds,
    )
    var sourceHook: suspend (ReservationId, ParticipantId) -> AdmissionLookup? = { _, _ -> null }
    var sourceCalls = 0
    val source = object : AdmissionSource {
        override suspend fun admission(reservation: ReservationId, participant: ParticipantId): AdmissionLookup {
            sourceCalls++
            return sourceHook(reservation, participant) ?: this@World.reservation.admission(reservation, participant)
        }
    }
    private var attempt = 0
    val core = createCheckInCore(source, checkIns, clock, coroutines, { CheckInAttemptId("a-${++attempt}") }, 30.seconds)

    fun reserve(who: List<ParticipantId> = listOf(alice, bob)): ReservationRecord.Reserved {
        reservation.selectWorkshop(session)
        who.forEach { reservation.toggleParticipant(it) }
        val draft = (reservation.confirmWithoutQualification() as ConfirmOutcome.Confirmed).draft
        reservation.submit(draft)
        scope.runCurrent()
        return reservation.snapshot.value.reservations.last() as ReservationRecord.Reserved
    }

    fun admission(record: ReservationRecord.Reserved, who: ParticipantId): ReservationAdmission =
        record.admissions.single { it.participant == who }

    fun status(record: ReservationRecord.Reserved, who: ParticipantId): ParticipantCheckIn? =
        core.snapshot.value.entries[CheckInKey(record.reservation, who)]
}

@OptIn(ExperimentalCoroutinesApi::class)
class CheckInCoreTest {
    @Test
    fun `二人の予約でAだけ受け付けると、Aだけ受付済みでBは未受付のまま`() = runTest {
        val w = World(this)
        val record = w.reserve()
        w.now = START - 10 * MINUTE
        assertEquals(CheckInAcceptance.Accepted, w.core.checkIn(w.admission(record, alice)))
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, w.status(record, alice))
        assertNull(w.status(record, bob))
        assertEquals(1, w.checkIns.creates.size)
    }

    @Test
    fun `連打は処理中を確保した最初の一回だけが受付作成要求になり、受付済みの再操作も要求しない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        val admission = w.admission(record, alice)
        assertEquals(CheckInAcceptance.Accepted, w.core.checkIn(admission))
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Busy), w.core.checkIn(admission))
        runCurrent()
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.AlreadyCheckedIn), w.core.checkIn(admission))
        runCurrent()
        assertEquals(1, w.checkIns.creates.size)
        assertEquals(1, w.sourceCalls)
    }

    @Test
    fun `予約の確認に戻る前でも処理中は確保済みで、確認の応答待ちの間の再操作は予約確認を重ねない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        val gate = CompletableDeferred<Unit>()
        w.sourceHook = { _, _ -> gate.await(); null }
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        assertEquals(ParticipantCheckIn.Processing(ProcessingStage.ConfirmingReservation), w.status(record, alice))
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Busy), w.core.checkIn(w.admission(record, alice)))
        assertEquals(CheckInAcceptance.Accepted, w.core.checkIn(w.admission(record, bob)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, w.status(record, alice))
        assertEquals(ParticipantCheckIn.CheckedIn, w.status(record, bob))
        assertEquals(2, w.checkIns.creates.size)
    }

    @Test
    fun `受付開始ちょうどは受け付け、その直前は要求も予約の照会も行わない`() = runTest {
        val w = World(this)
        val record = w.reserve()
        w.now = START - 30 * MINUTE - 1
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.NotYetOpen), w.core.checkIn(w.admission(record, alice)))
        runCurrent()
        assertEquals(0, w.sourceCalls)
        assertEquals(0, w.checkIns.creates.size)
        w.now = START - 30 * MINUTE
        assertEquals(CheckInAcceptance.Accepted, w.core.checkIn(w.admission(record, alice)))
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, w.status(record, alice))
    }

    @Test
    fun `受付終了の直前は受け付け、受付終了ちょうどは要求を送らない`() = runTest {
        val w = World(this)
        val record = w.reserve()
        w.now = START + 15 * MINUTE
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Closed), w.core.checkIn(w.admission(record, alice)))
        runCurrent()
        assertEquals(0, w.checkIns.creates.size)
        w.now = START + 15 * MINUTE - 1
        assertEquals(CheckInAcceptance.Accepted, w.core.checkIn(w.admission(record, alice)))
        runCurrent()
        assertEquals(1, w.checkIns.creates.size)
    }

    @Test
    fun `予約の確認から戻った時点で受付時間を取り直し、過ぎていれば要求を送らない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        w.sourceHook = { _, _ -> w.now = START + 15 * MINUTE; null }
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        assertEquals(Denial.Closed, assertIs<ParticipantCheckIn.NotAdmitted>(w.status(record, alice)).denial)
        assertEquals(0, w.checkIns.creates.size)
    }

    @Test
    fun `応答だけを失った受付は結果不明で元の試行を照会し、新しい受付は作らない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        w.checkIns.onCreate = { CheckInCreateObservation.NoResponse }
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        val unknown = assertIs<ParticipantCheckIn.Unknown>(w.status(record, alice))
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Unresolved), w.core.checkIn(w.admission(record, alice)))
        assertEquals(CheckInAcceptance.Accepted, w.core.checkStatus(unknown.check))
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, w.status(record, alice))
        assertEquals(w.checkIns.creates, w.checkIns.lookups)
        assertEquals(1, w.checkIns.creates.size)
    }

    @Test
    fun `照会しても見つからない・失敗した場合は結果不明のままで、受付時間の終了後も照会できる`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        w.checkIns.onCreate = { CheckInCreateObservation.NoResponse }
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        w.now = START + HOUR
        w.checkIns.onLookup = { CheckInLookupObservation.NotFound }
        w.core.checkStatus(assertIs<ParticipantCheckIn.Unknown>(w.status(record, alice)).check)
        runCurrent()
        val stillUnknown = assertIs<ParticipantCheckIn.Unknown>(w.status(record, alice))
        w.checkIns.onLookup = { CheckInLookupObservation.Failed }
        w.core.checkStatus(stillUnknown.check)
        runCurrent()
        assertIs<ParticipantCheckIn.Unknown>(w.status(record, alice))
        assertEquals(1, w.checkIns.creates.size)
        assertEquals(2, w.checkIns.lookups.size)
    }

    @Test
    fun `未成立が確定した後の再試行は明示操作で、新しい試行の識別子を使い、古い券では再度行えない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        w.checkIns.onCreate = { CheckInCreateObservation.NotCreated }
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        val retry = assertIs<ParticipantCheckIn.NotCreated>(w.status(record, alice)).retry
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Unresolved), w.core.checkIn(w.admission(record, alice)))
        w.checkIns.onCreate = { CheckInCreateObservation.Recorded }
        assertEquals(CheckInAcceptance.Accepted, w.core.retry(retry))
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, w.status(record, alice))
        assertEquals(2, w.checkIns.creates.size)
        assertNotEquals(w.checkIns.creates[0], w.checkIns.creates[1])
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Stale), w.core.retry(retry))
    }

    @Test
    fun `T-72時間の予約情報を取消後に使っても、現在の予約を確認し直して受付作成要求を送らない`() = runTest {
        val w = World(this)
        val record = w.reserve()
        val old = w.admission(record, alice)
        w.now = START - 48 * HOUR
        w.reservation.cancel(record.cancel)
        runCurrent()
        w.now = START - 20 * MINUTE
        w.core.checkIn(old)
        runCurrent()
        val denial = assertIs<ParticipantCheckIn.NotAdmitted>(w.status(record, alice)).denial
        assertEquals(AdmissionUnavailableReason.Cancelled, assertIs<Denial.ReservationUnavailable>(denial).reason)
        assertEquals(0, w.checkIns.creates.size)
        assertNull(w.core.snapshot.value.entries[CheckInKey(record.reservation, bob)])
    }

    @Test
    fun `取消の応答が失われた予約はサービスの現在の状態で取消済みと判断し、確認できなければ結果不明とは別の拒否になる`() = runTest {
        val w = World(this)
        val record = w.reserve()
        w.now = START - 48 * HOUR
        w.reservations.cancel = CancelObservation.NoResponse
        w.reservation.cancel(record.cancel)
        runCurrent()
        w.reservations.usage = UsageObservation.Cancelled
        w.now = START - 20 * MINUTE
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        assertIs<Denial.ReservationUnavailable>(assertIs<ParticipantCheckIn.NotAdmitted>(w.status(record, alice)).denial)

        w.reservations.usage = UsageObservation.Failed
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        assertEquals(Denial.ReservationUnconfirmed, assertIs<ParticipantCheckIn.NotAdmitted>(w.status(record, alice)).denial)
        assertEquals(0, w.checkIns.creates.size)
    }

    @Test
    fun `予約の確認が別の参加者や別の予約の前提を返しても、対象が一致しなければ受付を要求しない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve()
        val other = w.admission(record, bob)
        w.sourceHook = { _, _ -> AdmissionLookup.Valid(other) }
        w.core.checkIn(w.admission(record, alice))
        runCurrent()
        assertEquals(Denial.WrongTarget, assertIs<ParticipantCheckIn.NotAdmitted>(w.status(record, alice)).denial)
        assertEquals(0, w.checkIns.creates.size)
    }

    @Test
    fun `予約に含まれない参加者の組では予約の確認が含まれないと返し、受付作成要求を送らない`() = runTest {
        val w = World(this, START - 10 * MINUTE)
        val record = w.reserve(listOf(alice))
        assertEquals(
            AdmissionUnavailableReason.NotIncluded,
            assertIs<AdmissionLookup.Unavailable>(w.source.admission(record.reservation, bob)).reason,
        )
        assertEquals(0, w.checkIns.creates.size)
    }
}
