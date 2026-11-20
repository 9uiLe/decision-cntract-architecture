package example.reservation.core

import example.reservation.ports.CancelObservation
import example.reservation.ports.CreateObservation
import example.reservation.ports.LookupObservation
import example.reservation.ports.ParticipantId
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationId
import example.reservation.ports.ReservationPort
import example.reservation.ports.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val HOUR = 3_600_000L
private const val START = 100 * HOUR
private const val CANCEL_DEADLINE = START - 24 * HOUR

private val pottery = Workshop(SessionId("pottery"), "陶芸", EpochMillis(START), 4)
private val cooking = Workshop(SessionId("cooking"), "料理", EpochMillis(START + 10 * HOUR), 4)
private val alice = ParticipantId("alice")
private val bob = ParticipantId("bob")
private val catalog = Catalog(
    listOf(pottery, cooking),
    listOf(Participant(alice, "アリス"), Participant(bob, "ボブ")),
)

private class ScriptedPort : ReservationPort {
    val createCalls = mutableListOf<RequestId>()
    val lookupCalls = mutableListOf<RequestId>()
    val cancelCalls = mutableListOf<ReservationId>()
    var onCreate: suspend (RequestId) -> CreateObservation = { CreateObservation.Created(ReservationId("r-1")) }
    var onLookup: suspend (RequestId) -> LookupObservation = { LookupObservation.NotFound }
    var onCancel: suspend (ReservationId) -> CancelObservation = { CancelObservation.Cancelled }

    override suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
        createCalls += request
        return onCreate(request)
    }

    override suspend fun lookup(request: RequestId): LookupObservation {
        lookupCalls += request
        return onLookup(request)
    }

    override suspend fun cancel(reservation: ReservationId): CancelObservation {
        cancelCalls += reservation
        return onCancel(reservation)
    }
}

private class Fixture(private val scope: TestScope, var now: Long = START - HOUR, timeout: kotlin.time.Duration = 30.seconds) {
    val port = ScriptedPort()
    private var counter = 0
    val core = createReservationCore(
        catalog, port, { EpochMillis(now) }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), { RequestId("req-${++counter}") }, timeout,
    )
    val snapshot get() = core.snapshot.value

    fun confirmed(workshop: Workshop = pottery, who: List<ParticipantId> = listOf(alice)): ConfirmedDraft {
        core.selectWorkshop(workshop.id)
        who.forEach { core.toggleParticipant(it) }
        return (core.confirm() as ConfirmOutcome.Confirmed).draft
    }

    fun reserve(workshop: Workshop = pottery): ReservationRecord.Reserved {
        core.submit(confirmed(workshop))
        scope.runCurrent()
        return snapshot.reservations.last() as ReservationRecord.Reserved
    }
}

private fun Acceptance.rejection() = (this as Acceptance.Rejected).reason

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationCoreTest {
    @Test
    fun `開始時刻ちょうどは作成要求を送らず、直前は送る`() = runTest {
        val atStart = Fixture(this, now = START)
        assertEquals(Rejection.RegistrationClosed, atStart.core.submit(atStart.confirmed()).rejection())
        runCurrent()
        assertTrue(atStart.port.createCalls.isEmpty())
        val closed = assertIs<ActiveRequest.RegistrationClosed>(atStart.snapshot.request)
        assertEquals(pottery, closed.workshop)

        val before = Fixture(this, now = START - 1)
        assertEquals(Acceptance.Accepted, before.core.submit(before.confirmed()))
        runCurrent()
        assertEquals(1, before.port.createCalls.size)
    }

    @Test
    fun `確認を変更すると古い確認では送れない`() = runTest {
        val f = Fixture(this)
        val old = f.confirmed()
        f.core.toggleParticipant(bob)
        assertEquals(Rejection.Stale, f.core.submit(old).rejection())
        val latest = (f.core.confirm() as ConfirmOutcome.Confirmed).draft
        assertEquals(Acceptance.Accepted, f.core.submit(latest))
        runCurrent()
        assertEquals(1, f.port.createCalls.size)
    }

    @Test
    fun `参加者が空では確認できず、確認だけでは要求を送らない`() = runTest {
        val f = Fixture(this)
        f.core.selectWorkshop(pottery.id)
        assertEquals(ConfirmOutcome.EmptyParticipants, f.core.confirm())
        f.core.toggleParticipant(alice)
        assertIs<ConfirmOutcome.Confirmed>(f.core.confirm())
        runCurrent()
        assertTrue(f.port.createCalls.isEmpty())
    }

    @Test
    fun `送信の連打は作成要求を一度だけ送り、二回目は Busy で拒否する`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<CreateObservation>()
        f.port.onCreate = { gate.await() }
        val draft = f.confirmed()

        assertEquals(Acceptance.Accepted, f.core.submit(draft))
        assertEquals(Rejection.Busy, f.core.submit(draft).rejection())
        runCurrent()
        assertIs<ActiveRequest.Creating>(f.snapshot.request)
        assertEquals(1, f.port.createCalls.size)

        gate.complete(CreateObservation.Created(ReservationId("r-1")))
        runCurrent()
        assertEquals(1, f.snapshot.reservations.size)
        assertEquals(Rejection.Stale, f.core.submit(draft).rejection())
        assertEquals(1, f.port.createCalls.size)
    }

    @Test
    fun `応答喪失は結果不明を保ち、開始時刻を過ぎても元の RequestId で照会する`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.NoResponse }
        f.core.submit(f.confirmed())
        runCurrent()
        val unknown = assertIs<ActiveRequest.Unknown>(f.snapshot.request)
        assertEquals(RequestId("req-1"), unknown.check.request)

        f.now = START + 10 * HOUR
        f.port.onLookup = { LookupObservation.NotFound }
        f.core.checkStatus(unknown.check)
        runCurrent()
        val stillUnknown = assertIs<ActiveRequest.Unknown>(f.snapshot.request)

        f.port.onLookup = { LookupObservation.Failed }
        f.core.checkStatus(stillUnknown.check)
        runCurrent()
        val afterFailure = assertIs<ActiveRequest.Unknown>(f.snapshot.request)

        f.port.onLookup = { LookupObservation.Found(ReservationId("r-9")) }
        f.core.checkStatus(afterFailure.check)
        runCurrent()
        assertNull(f.snapshot.request)
        assertEquals(ReservationId("r-9"), f.snapshot.reservations.single().reservation)
        assertEquals(List(3) { RequestId("req-1") }, f.port.lookupCalls)
        assertEquals(1, f.port.createCalls.size)
    }

    @Test
    fun `結果不明の間は新しい送信を受け付けず、別の開催回の下書きは作れる`() = runTest {
        val f = Fixture(this)
        f.reserve()
        f.port.onCreate = { CreateObservation.NoResponse }
        f.core.submit(f.confirmed(cooking))
        runCurrent()
        val unknown = assertIs<ActiveRequest.Unknown>(f.snapshot.request)

        f.core.selectWorkshop(pottery.id)
        f.core.toggleParticipant(bob)
        val draft = (f.core.confirm() as ConfirmOutcome.Confirmed).draft
        assertEquals(Rejection.Busy, f.core.submit(draft).rejection())
        assertEquals(pottery, f.snapshot.draft.workshop)
        assertEquals(unknown, f.snapshot.request)
        assertEquals(2, f.port.createCalls.size)
    }

    @Test
    fun `結果不明を保持したまま別の確定予約を取り消し、決着後に結果不明へ戻る`() = runTest {
        val f = Fixture(this, now = CANCEL_DEADLINE - HOUR)
        val reserved = f.reserve()
        f.port.onCreate = { CreateObservation.NoResponse }
        f.core.submit(f.confirmed(cooking))
        runCurrent()
        val unknown = assertIs<ActiveRequest.Unknown>(f.snapshot.request)

        val gate = CompletableDeferred<CancelObservation>()
        f.port.onCancel = { gate.await() }
        assertEquals(Acceptance.Accepted, f.core.cancel(reserved.cancel))
        runCurrent()
        val cancelling = assertIs<ActiveRequest.Cancelling>(f.snapshot.request)
        assertEquals(unknown, cancelling.resting)
        assertEquals(Rejection.Busy, f.core.checkStatus(unknown.check).rejection())
        assertEquals(0, f.port.lookupCalls.size)
        assertEquals(2, f.port.createCalls.size)

        gate.complete(CancelObservation.Cancelled)
        runCurrent()
        assertEquals(unknown, f.snapshot.request)
        assertIs<ReservationRecord.Cancelled>(f.snapshot.reservations.single())
        f.port.onLookup = { LookupObservation.Found(ReservationId("r-2")) }
        assertEquals(Acceptance.Accepted, f.core.checkStatus(unknown.check))
        runCurrent()
        assertEquals(2, f.snapshot.reservations.size)
    }

    @Test
    fun `未成立確定は別の確定予約の取消で失われず、取消後も再試行できる`() = runTest {
        val f = Fixture(this, now = CANCEL_DEADLINE - HOUR)
        val reserved = f.reserve()
        f.port.onCreate = { CreateObservation.NotCreated }
        f.core.submit(f.confirmed(cooking))
        runCurrent()
        val notCreated = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)

        assertEquals(Acceptance.Accepted, f.core.cancel(reserved.cancel))
        runCurrent()
        assertEquals(notCreated, f.snapshot.request)
        assertIs<ReservationRecord.Cancelled>(f.snapshot.reservations.single())

        f.port.onCreate = { CreateObservation.Created(ReservationId("r-2")) }
        assertEquals(Acceptance.Accepted, f.core.retry(notCreated.retry))
        runCurrent()
        assertEquals(2, f.snapshot.reservations.size)
    }

    @Test
    fun `外部要求の実行中は取消を受け付けない`() = runTest {
        val f = Fixture(this, now = CANCEL_DEADLINE - HOUR)
        val reserved = f.reserve()
        val gate = CompletableDeferred<CreateObservation>()
        f.port.onCreate = { gate.await() }
        f.core.submit(f.confirmed(cooking))
        runCurrent()
        assertIs<ActiveRequest.Creating>(f.snapshot.request)
        assertEquals(Rejection.Busy, f.core.cancel(reserved.cancel).rejection())
        assertTrue(f.port.cancelCalls.isEmpty())
    }

    @Test
    fun `受付終了で拒否された送信は結果を残し、別の開催回を選ぶと消える`() = runTest {
        val f = Fixture(this, now = START)
        val draft = f.confirmed()
        assertEquals(Rejection.RegistrationClosed, f.core.submit(draft).rejection())
        assertIs<ActiveRequest.RegistrationClosed>(f.snapshot.request)
        assertEquals(Rejection.RegistrationClosed, f.core.submit(draft).rejection())
        runCurrent()
        assertTrue(f.port.createCalls.isEmpty())

        f.core.selectWorkshop(cooking.id)
        assertNull(f.snapshot.request)
    }

    @Test
    fun `未成立がある間の受付終了の拒否は、未成立の再試行を失わせない`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.NotCreated }
        f.core.submit(f.confirmed())
        runCurrent()
        val notCreated = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)

        f.now = START
        val again = (f.core.confirm() as ConfirmOutcome.Confirmed).draft
        assertEquals(Rejection.RegistrationClosed, f.core.submit(again).rejection())
        assertEquals(notCreated, f.snapshot.request)
    }

    @Test
    fun `未成立確定後の再試行は新しい RequestId を使い、その応答喪失は新しい ID で照会する`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.NotCreated }
        f.core.submit(f.confirmed())
        runCurrent()
        val notCreated = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)

        f.port.onCreate = { CreateObservation.NoResponse }
        assertEquals(Acceptance.Accepted, f.core.retry(notCreated.retry))
        runCurrent()
        val unknown = assertIs<ActiveRequest.Unknown>(f.snapshot.request)
        assertEquals(listOf(RequestId("req-1"), RequestId("req-2")), f.port.createCalls)
        assertEquals(RequestId("req-2"), unknown.check.request)
        assertEquals(Rejection.Stale, f.core.retry(notCreated.retry).rejection())
        assertEquals(2, f.port.createCalls.size)
    }

    @Test
    fun `開始時刻以降の再試行は拒否し、未成立の事実を保持する`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.NotCreated }
        f.core.submit(f.confirmed())
        runCurrent()
        val notCreated = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)

        f.now = START
        assertEquals(Rejection.RegistrationClosed, f.core.retry(notCreated.retry).rejection())
        runCurrent()
        assertEquals(1, f.port.createCalls.size)
        assertEquals(notCreated, f.snapshot.request)
    }

    @Test
    fun `照会で未成立が確定すると再試行できる`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.NoResponse }
        f.core.submit(f.confirmed())
        runCurrent()
        f.port.onLookup = { LookupObservation.NotCreated }
        f.core.checkStatus((f.snapshot.request as ActiveRequest.Unknown).check)
        runCurrent()
        assertIs<ActiveRequest.NotCreated>(f.snapshot.request)
    }

    @Test
    fun `照会を逆順に解放しても確定した予約を結果不明へ戻さない`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.NoResponse }
        f.core.submit(f.confirmed())
        runCurrent()
        val first = (f.snapshot.request as ActiveRequest.Unknown).check
        val late = CompletableDeferred<LookupObservation>()
        f.port.onLookup = { late.await() }
        f.core.checkStatus(first)
        runCurrent()
        assertEquals(Rejection.Busy, f.core.checkStatus(first).rejection())

        late.complete(LookupObservation.Found(ReservationId("r-1")))
        runCurrent()
        assertEquals(1, f.snapshot.reservations.size)
        assertEquals(Rejection.Stale, f.core.checkStatus(first).rejection())
        assertEquals(1, f.port.lookupCalls.size)
        assertNull(f.snapshot.request)
    }

    @Test
    fun `時間切れの応答は結果不明になり、後から届いた作成の応答は適用しない`() = runTest {
        val f = Fixture(this, timeout = 5.seconds)
        val never = CompletableDeferred<CreateObservation>()
        f.port.onCreate = { never.await() }
        f.core.submit(f.confirmed())
        advanceTimeBy(4_999)
        assertIs<ActiveRequest.Creating>(f.snapshot.request)
        advanceTimeBy(2)
        assertIs<ActiveRequest.Unknown>(f.snapshot.request)

        never.complete(CreateObservation.Created(ReservationId("late")))
        runCurrent()
        assertTrue(f.snapshot.reservations.isEmpty())
        assertEquals(1, f.port.createCalls.size)
    }

    @Test
    fun `Port の例外と Port 側のキャンセルは処理中のまま残さない`() = runTest {
        val thrown = Fixture(this)
        thrown.port.onCreate = { error("接続できません") }
        thrown.core.submit(thrown.confirmed())
        runCurrent()
        assertIs<ActiveRequest.Unknown>(thrown.snapshot.request)

        val cancelled = Fixture(this)
        cancelled.port.onCreate = { throw kotlinx.coroutines.CancellationException("port") }
        cancelled.core.submit(cancelled.confirmed())
        runCurrent()
        assertIs<ActiveRequest.Unknown>(cancelled.snapshot.request)
    }

    @Test
    fun `画面の購読が無くても送信の結果は Core に残り、あとから読める`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<CreateObservation>()
        f.port.onCreate = { gate.await() }
        f.core.submit(f.confirmed())
        runCurrent()
        gate.complete(CreateObservation.Created(ReservationId("r-1")))
        runCurrent()
        assertIs<ReservationRecord.Reserved>(f.core.snapshot.value.reservations.single())
    }

    @Test
    fun `満席は予約を作らず、開催回や参加者を選び直すと結果を消す`() = runTest {
        val f = Fixture(this)
        f.port.onCreate = { CreateObservation.Full }
        f.core.submit(f.confirmed())
        runCurrent()
        assertIs<ActiveRequest.SoldOut>(f.snapshot.request)

        f.core.selectWorkshop(cooking.id)
        assertNull(f.snapshot.request)
        assertTrue(f.snapshot.reservations.isEmpty())
    }

    @Test
    fun `確定後に別の開催回を選んでも元の予約を取り消せる`() = runTest {
        val f = Fixture(this, now = CANCEL_DEADLINE - HOUR)
        val reserved = f.reserve()
        f.core.selectWorkshop(cooking.id)
        f.core.toggleParticipant(bob)
        assertEquals(cooking, f.snapshot.draft.workshop)

        assertEquals(Acceptance.Accepted, f.core.cancel(reserved.cancel))
        runCurrent()
        assertIs<ReservationRecord.Cancelled>(f.snapshot.reservations.single())
        assertEquals(1, f.port.cancelCalls.size)
    }

    @Test
    fun `取消期限ちょうどは取消要求を送らず予約を維持し、直前は送る`() = runTest {
        val f = Fixture(this)
        val reserved = f.reserve()
        f.now = CANCEL_DEADLINE
        assertEquals(Rejection.CancellationClosed, f.core.cancel(reserved.cancel).rejection())
        runCurrent()
        assertTrue(f.port.cancelCalls.isEmpty())
        assertIs<ReservationRecord.Reserved>(f.snapshot.reservations.single())

        f.now = CANCEL_DEADLINE - 1
        assertEquals(Acceptance.Accepted, f.core.cancel(reserved.cancel))
        runCurrent()
        assertEquals(1, f.port.cancelCalls.size)
    }

    @Test
    fun `取消の保留中に連打しても取消要求は一度で、期限を越えても送信済みの結果を採用する`() = runTest {
        val f = Fixture(this, now = CANCEL_DEADLINE - HOUR)
        val reserved = f.reserve()
        val gate = CompletableDeferred<CancelObservation>()
        f.port.onCancel = { gate.await() }

        assertEquals(Acceptance.Accepted, f.core.cancel(reserved.cancel))
        assertEquals(Rejection.Busy, f.core.cancel(reserved.cancel).rejection())
        f.now = CANCEL_DEADLINE + HOUR
        gate.complete(CancelObservation.Cancelled)
        runCurrent()
        assertIs<ReservationRecord.Cancelled>(f.snapshot.reservations.single())
        assertEquals(Rejection.Stale, f.core.cancel(reserved.cancel).rejection())
        assertEquals(1, f.port.cancelCalls.size)
    }

    @Test
    fun `取消が拒否・喪失されても予約の事実を保ち、新しい券で再度取り消せる`() = runTest {
        val f = Fixture(this, now = CANCEL_DEADLINE - HOUR)
        val reserved = f.reserve()
        f.port.onCancel = { CancelObservation.Declined }
        f.core.cancel(reserved.cancel)
        runCurrent()
        val afterDecline = assertIs<ReservationRecord.Reserved>(f.snapshot.reservations.single())
        assertEquals(CancelFailure.Declined, afterDecline.lastCancelFailure)
        assertEquals(Rejection.Stale, f.core.cancel(reserved.cancel).rejection())

        f.port.onCancel = { CancelObservation.NoResponse }
        f.core.cancel(afterDecline.cancel)
        runCurrent()
        val afterLoss = assertIs<ReservationRecord.Reserved>(f.snapshot.reservations.single())
        assertEquals(CancelFailure.NoResponse, afterLoss.lastCancelFailure)
        assertEquals(2, f.port.cancelCalls.size)
    }

    @Test
    fun `未知の開催回と参加者は拒否する`() = runTest {
        val f = Fixture(this)
        assertEquals(Rejection.UnknownWorkshop, f.core.selectWorkshop(SessionId("none")).rejection())
        assertEquals(Rejection.UnknownWorkshop, f.core.toggleParticipant(alice).rejection())
        f.core.selectWorkshop(pottery.id)
        assertEquals(Rejection.UnknownParticipant, f.core.toggleParticipant(ParticipantId("none")).rejection())
    }
}
