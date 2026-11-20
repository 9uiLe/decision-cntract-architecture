package example.adapter.simulator

import example.checkin.core.CheckInAcceptance
import example.checkin.core.CheckInAttemptId
import example.checkin.core.CheckInCore
import example.checkin.core.CheckInCreateObservation
import example.checkin.core.CheckInKey
import example.checkin.core.CheckInRejection
import example.checkin.core.Denial
import example.checkin.core.ParticipantCheckIn
import example.checkin.core.createCheckInCore
import example.eligibility.core.QualificationKind
import example.eligibility.core.createEligibilityCore
import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.AdmissionLookup
import example.reservation.core.AdmissionUnavailableReason
import example.reservation.core.Catalog
import example.reservation.core.ConfirmOutcome
import example.reservation.core.ConfirmedDraft
import example.reservation.core.NewBookingRequirement
import example.reservation.core.Participant
import example.reservation.core.RequestId
import example.reservation.core.ReservationAdmission
import example.reservation.core.ReservationId
import example.reservation.core.ReservationRecord
import example.reservation.core.Workshop
import example.reservation.core.createReservationCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
private const val START = 1_000 * HOUR
private val pottery = QualificationKind("pottery-advanced", "上級ろくろ修了")
private val roasting = QualificationKind("roasting-advanced", "上級焙煎修了")
private val akari = ParticipantId("akari")
private val kota = ParticipantId("kota")
private val minami = ParticipantId("minami")
private val open = Workshop(SessionId("open"), "通常回", EpochMillis(START), 4, NewBookingRequirement.NotRequired)
private val advanced = Workshop(SessionId("advanced"), "上級回", EpochMillis(START), 4, NewBookingRequirement.Required(pottery))
private val catalog = Catalog(
    listOf(open, advanced),
    listOf(Participant(akari, "あかり"), Participant(kota, "こうた"), Participant(minami, "みなみ")),
)

@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(private val scope: TestScope, var now: Long = START - 72 * HOUR) {
    val sim = createSimulator(
        mapOf(open.id to 4, advanced.id to 4),
        listOf(
            QualificationHolding(akari, pottery, EpochMillis(START + 10 * HOUR)),
            QualificationHolding(kota, pottery, EpochMillis(START + 10 * HOUR)),
        ),
    )
    private val coroutines = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    private val clock = Clock { EpochMillis(now) }
    private var request = 0
    private var attempt = 0
    val eligibility = createEligibilityCore(sim.eligibility, clock, coroutines, 30.seconds)
    val reservation = createReservationCore(
        catalog, sim.reservation, eligibility, clock, coroutines, { RequestId("req-${++request}") }, 30.seconds,
    )
    val checkIn: CheckInCore = createCheckInCore(reservation, sim.checkIn, clock, coroutines, { CheckInAttemptId("att-${++attempt}") }, 30.seconds)
    val observations get() = sim.controls.observations.value

    fun reserveOpen(who: List<ParticipantId> = listOf(akari, kota)): ReservationRecord.Reserved {
        reservation.selectWorkshop(open.id)
        who.forEach { reservation.toggleParticipant(it) }
        reservation.submit((reservation.confirmWithoutQualification() as ConfirmOutcome.Confirmed).draft)
        scope.runCurrent()
        return reservation.snapshot.value.reservations.last() as ReservationRecord.Reserved
    }

    fun selectAdvanced(who: List<ParticipantId>) {
        reservation.selectWorkshop(advanced.id)
        who.forEach { reservation.toggleParticipant(it) }
    }

    fun verifyQualifications() {
        reservation.requestEligibility()
        scope.runCurrent()
    }

    fun confirmAdvanced(): ConfirmedDraft = (reservation.confirmQualified() as ConfirmOutcome.Confirmed).draft

    fun reserveAdvanced(who: List<ParticipantId> = listOf(akari, kota)): ReservationRecord.Reserved {
        selectAdvanced(who)
        verifyQualifications()
        reservation.submit(confirmAdvanced())
        scope.runCurrent()
        return reservation.snapshot.value.reservations.last() as ReservationRecord.Reserved
    }

    fun admission(record: ReservationRecord.Reserved, who: ParticipantId): ReservationAdmission =
        record.admissions.single { it.participant == who }

    fun checkInState(record: ReservationRecord.Reserved, who: ParticipantId): ParticipantCheckIn? =
        checkIn.snapshot.value.entries[CheckInKey(record.reservation, who)]

    fun recordsFor(record: ReservationRecord.Reserved, who: ParticipantId): Int =
        observations.checkInRecords.firstOrNull { it.reservation == record.reservation && it.participant == who }?.records ?: 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class AcceptanceTest {
    @Test
    fun `MC-01 通常回は資格を確認せず既存の条件で予約でき、資格サービスを呼ばない`() = runTest {
        val h = Harness(this)
        h.reserveOpen()
        assertEquals(0, h.observations.qualificationRequests)
        assertEquals(1, h.observations.createRequests)
        assertEquals(1, h.observations.activeReservations)
    }

    @Test
    fun `MC-02 要資格回で全員の資格が有効なら予約でき、作成要求は一度`() = runTest {
        val h = Harness(this)
        h.reserveAdvanced()
        assertEquals(1, h.observations.createRequests)
        assertEquals(1, h.observations.activeReservations)
        assertEquals(4, h.observations.qualificationRequests)
    }

    @Test
    fun `MC-03 一人でも未取得・期限切れなら予約作成要求を送らない`() = runTest {
        val h = Harness(this)
        h.selectAdvanced(listOf(akari, minami))
        h.verifyQualifications()
        assertEquals(listOf(minami), (h.reservation.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)
        h.sim.controls.grantQualification(minami, pottery, EpochMillis(h.now))
        h.verifyQualifications()
        assertEquals(listOf(minami), (h.reservation.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)
        assertEquals(0, h.observations.createRequests)
    }

    @Test
    fun `MC-04 資格の確認結果が不明なら有効と推測せず、予約作成要求を送らない`() = runTest {
        val h = Harness(this)
        h.selectAdvanced(listOf(akari))
        h.sim.controls.enqueueQualification(QualificationBehavior.NoResponse)
        h.verifyQualifications()
        assertIs<ConfirmOutcome.EligibilityNotMet>(h.reservation.confirmQualified())
        h.sim.controls.enqueueQualification(QualificationBehavior.Unrecognized)
        h.verifyQualifications()
        assertIs<ConfirmOutcome.EligibilityNotMet>(h.reservation.confirmQualified())
        assertEquals(0, h.observations.createRequests)
        h.verifyQualifications()
        assertIs<ConfirmOutcome.Confirmed>(h.reservation.confirmQualified())
        assertEquals(0, h.observations.createRequests)
    }

    @Test
    fun `MC-06 資格確認後に参加者や必要資格を変更すると、現在の条件の確認なしには予約できない`() = runTest {
        val h = Harness(this)
        h.selectAdvanced(listOf(akari, kota))
        h.verifyQualifications()
        val confirmed = h.confirmAdvanced()
        h.reservation.toggleParticipant(minami)
        assertEquals(example.reservation.core.Acceptance.Rejected(example.reservation.core.Rejection.Stale), h.reservation.submit(confirmed))
        h.reservation.selectWorkshop(open.id)
        assertEquals(example.reservation.core.Acceptance.Rejected(example.reservation.core.Rejection.Stale), h.reservation.submit(confirmed))
        assertEquals(ConfirmOutcome.QualificationNotRequired, h.reservation.confirmQualified())
        assertEquals(0, h.observations.createRequests)
    }

    @Test
    fun `MC-07 確認後で送信前に有効期限を迎えると、再確認で無効を判定して作成要求を送らない`() = runTest {
        val h = Harness(this)
        h.selectAdvanced(listOf(akari, kota))
        h.verifyQualifications()
        val confirmed = h.confirmAdvanced()
        h.sim.controls.grantQualification(kota, pottery, EpochMillis(h.now + HOUR))
        h.now += HOUR
        h.reservation.submit(confirmed)
        runCurrent()
        assertEquals(0, h.observations.createRequests)
        assertEquals(0, h.observations.activeReservations)
    }

    @Test
    fun `MC-09 二人の予約でAだけ受け付けると、Aだけ受付済みでBは未受付のまま、記録は一件`() = runTest {
        val h = Harness(this)
        val record = h.reserveOpen()
        h.now = START - 10 * MINUTE
        h.checkIn.checkIn(h.admission(record, akari))
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, h.checkInState(record, akari))
        assertNull(h.checkInState(record, kota))
        assertEquals(1, h.recordsFor(record, akari))
        assertEquals(0, h.recordsFor(record, kota))
    }

    @Test
    fun `MC-10 同じ参加者の連打は、受付作成要求も受付記録も重複しない`() = runTest {
        val h = Harness(this, START - 10 * MINUTE)
        val record = h.reserveOpen()
        val admission = h.admission(record, akari)
        repeat(5) { h.checkIn.checkIn(admission) }
        runCurrent()
        repeat(3) { h.checkIn.checkIn(admission) }
        runCurrent()
        assertEquals(1, h.observations.checkInCreateRequests)
        assertEquals(1, h.recordsFor(record, akari))
        assertEquals(0, h.observations.checkInRepeatedForRecordedTarget)
    }

    @Test
    fun `MC-12 MC-13 受付開始ちょうどは受け付け、受付終了ちょうどは受付作成要求を送らない`() = runTest {
        val h = Harness(this)
        val record = h.reserveOpen()
        h.now = START - 30 * MINUTE - 1
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.NotYetOpen), h.checkIn.checkIn(h.admission(record, akari)))
        h.now = START + 15 * MINUTE
        assertEquals(CheckInAcceptance.Rejected(CheckInRejection.Closed), h.checkIn.checkIn(h.admission(record, akari)))
        assertEquals(0, h.observations.checkInCreateRequests)
        h.now = START - 30 * MINUTE
        h.checkIn.checkIn(h.admission(record, akari))
        runCurrent()
        assertEquals(1, h.recordsFor(record, akari))
    }

    @Test
    fun `MC-14 受付が成立して応答だけを失うと、元の受付要求を照会し、記録は一件・作成要求は一回のまま`() = runTest {
        val h = Harness(this, START - 10 * MINUTE)
        val record = h.reserveOpen()
        h.sim.controls.enqueueCheckInCreate(CheckInBehavior.LoseResponseAfterRecord)
        h.checkIn.checkIn(h.admission(record, akari))
        runCurrent()
        val unknown = assertIs<ParticipantCheckIn.Unknown>(h.checkInState(record, akari))
        h.checkIn.checkIn(h.admission(record, akari))
        h.checkIn.checkStatus(unknown.check)
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, h.checkInState(record, akari))
        assertEquals(1, h.observations.checkInCreateRequests)
        assertEquals(1, h.observations.checkInLookupRequests)
        assertEquals(1, h.recordsFor(record, akari))
    }

    @Test
    fun `MC-15 予約自体が結果不明なら有効な予約として受付を成立させない`() = runTest {
        val h = Harness(this, START - 10 * MINUTE)
        h.sim.controls.enqueueCreate(CreateBehavior.LoseResponseAfterCreate)
        h.reservation.selectWorkshop(open.id)
        h.reservation.toggleParticipant(akari)
        h.reservation.submit((h.reservation.confirmWithoutQualification() as ConfirmOutcome.Confirmed).draft)
        runCurrent()
        assertEquals(1, h.observations.activeReservations)
        assertEquals(emptyList(), h.reservation.snapshot.value.reservations)
        assertEquals(
            AdmissionUnavailableReason.NotReserved,
            assertIs<AdmissionLookup.Unavailable>(h.reservation.admission(ReservationId("r-1"), akari)).reason,
        )
        assertEquals(0, h.observations.checkInCreateRequests)
    }

    @Test
    fun `MC-17 9-2 予約後に資格だけが失効しても、基準仕様では予約を維持して二人とも時間内に受付でき、来場時に資格を再確認しない`() = runTest {
        val h = Harness(this)
        val record = h.reserveAdvanced()
        h.sim.controls.grantQualification(kota, pottery, EpochMillis(h.now))
        h.now = START - 10 * MINUTE
        val before = h.observations.qualificationRequests
        h.checkIn.checkIn(h.admission(record, akari))
        h.checkIn.checkIn(h.admission(record, kota))
        runCurrent()
        assertEquals(ParticipantCheckIn.CheckedIn, h.checkInState(record, akari))
        assertEquals(ParticipantCheckIn.CheckedIn, h.checkInState(record, kota))
        assertEquals(before, h.observations.qualificationRequests)
        assertEquals(2, h.observations.checkInRecords.size)
    }

    @Test
    fun `9-1 T-72時間に成立した予約を取り消した後、古い情報でのチェックインは受付作成要求も記録も増やさない`() = runTest {
        val h = Harness(this)
        val record = h.reserveOpen()
        val stale = h.admission(record, akari)
        h.now = START - 48 * HOUR
        h.reservation.cancel(record.cancel)
        runCurrent()
        h.now = START - 20 * MINUTE
        h.checkIn.checkIn(stale)
        runCurrent()
        assertEquals(
            AdmissionUnavailableReason.Cancelled,
            assertIs<Denial.ReservationUnavailable>(assertIs<ParticipantCheckIn.NotAdmitted>(h.checkInState(record, akari)).denial).reason,
        )
        assertEquals(0, h.observations.checkInCreateRequests)
        assertEquals(emptyList(), h.observations.checkInRecords)
    }

    @Test
    fun `取消が成立して応答だけを失った予約でも、サービスの現在の状態で取消済みと判断し、受付作成要求を送らない`() = runTest {
        val h = Harness(this)
        val record = h.reserveOpen()
        h.now = START - 48 * HOUR
        h.sim.controls.enqueueCancel(CancelBehavior.Lose)
        h.reservation.cancel(record.cancel)
        runCurrent()
        assertIs<ReservationRecord.Reserved>(h.reservation.snapshot.value.reservations.single())
        h.now = START - 20 * MINUTE
        h.checkIn.checkIn(h.admission(record, akari))
        runCurrent()
        assertIs<Denial.ReservationUnavailable>(assertIs<ParticipantCheckIn.NotAdmitted>(h.checkInState(record, akari)).denial)
        assertEquals(0, h.observations.checkInCreateRequests)
        assertEquals(1, h.observations.usageRequests)
    }

    @Test
    fun `同じ予約と参加者への別の試行は、受付サービスが受付済みの別の観測として返し、記録を増やさず、数える`() = runTest {
        val h = Harness(this)
        val record = h.reserveOpen()
        assertEquals(CheckInCreateObservation.Recorded, h.sim.checkIn.create(CheckInAttemptId("x1"), record.reservation, akari))
        assertEquals(CheckInCreateObservation.AlreadyRecorded, h.sim.checkIn.create(CheckInAttemptId("x2"), record.reservation, akari))
        assertEquals(1, h.recordsFor(record, akari))
        assertEquals(2, h.observations.checkInCreateRequests)
        assertEquals(1, h.observations.checkInRepeatedForRecordedTarget)
    }

    @Test
    fun `受付サービスは予約の有効性や受付時間を判断しない`() = runTest {
        val h = Harness(this, START + 10 * HOUR)
        assertEquals(CheckInCreateObservation.Recorded, h.sim.checkIn.create(CheckInAttemptId("late"), ReservationId("none"), akari))
        assertEquals(0, h.observations.usageRequests)
    }
}
