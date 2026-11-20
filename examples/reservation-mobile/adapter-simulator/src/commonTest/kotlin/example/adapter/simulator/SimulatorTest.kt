package example.adapter.simulator

import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.CancelObservation
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.RequestId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val session = SessionId("s")
private val people = listOf(ParticipantId("a"))

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorTest {
    @Test
    fun `通常成立は予約を作り、同じ要求の照会で見つかる`() = runTest {
        val sim = createSimulator(mapOf(session to 2))
        val created = assertIs<CreateObservation.Created>(sim.reservation.create(RequestId("q1"), session, people))
        assertEquals(LookupObservation.Found(created.reservation), sim.reservation.lookup(RequestId("q1")))
        assertEquals(1, sim.controls.observations.value.activeReservations)
        assertEquals(LookupObservation.NotFound, sim.reservation.lookup(RequestId("other")))
    }

    @Test
    fun `応答喪失は予約が成立したまま応答だけを返さない`() = runTest {
        val sim = createSimulator(mapOf(session to 2))
        sim.controls.enqueueCreate(CreateBehavior.LoseResponseAfterCreate)
        assertEquals(CreateObservation.NoResponse, sim.reservation.create(RequestId("q1"), session, people))
        assertEquals(1, sim.controls.observations.value.activeReservations)
        assertIs<LookupObservation.Found>(sim.reservation.lookup(RequestId("q1")))
        assertEquals(1, sim.controls.observations.value.createRequests)
    }

    @Test
    fun `作成前の拒否と満席は予約を作らず、照会では未成立を返す`() = runTest {
        val sim = createSimulator(mapOf(session to 1))
        sim.controls.enqueueCreate(CreateBehavior.RejectBeforeCreate)
        sim.controls.enqueueCreate(CreateBehavior.SoldOut)
        assertEquals(CreateObservation.NotCreated, sim.reservation.create(RequestId("q1"), session, people))
        assertEquals(CreateObservation.Full, sim.reservation.create(RequestId("q2"), session, people))
        assertEquals(0, sim.controls.observations.value.activeReservations)
        assertEquals(LookupObservation.NotCreated, sim.reservation.lookup(RequestId("q2")))
    }

    @Test
    fun `定員を超える予約は満席になり、取消で席が戻る`() = runTest {
        val sim = createSimulator(mapOf(session to 1))
        val first = assertIs<CreateObservation.Created>(sim.reservation.create(RequestId("q1"), session, people))
        assertEquals(CreateObservation.Full, sim.reservation.create(RequestId("q2"), session, people))
        assertEquals(CancelObservation.Cancelled, sim.reservation.cancel(first.reservation))
        assertEquals(CancelObservation.Declined, sim.reservation.cancel(first.reservation))
        assertIs<CreateObservation.Created>(sim.reservation.create(RequestId("q3"), session, people))
        assertEquals(2, sim.controls.observations.value.cancelRequests)
    }

    @Test
    fun `保留した応答は解放まで返らず、呼び出し元がキャンセルしても保留の記録を残さない`() = runTest {
        val sim = createSimulator(mapOf(session to 2))
        sim.controls.enqueueCreate(CreateBehavior.HoldResponse)
        val call = async { sim.reservation.create(RequestId("q1"), session, people) }
        runCurrent()
        assertTrue(call.isActive)
        assertEquals(1, sim.controls.observations.value.heldCalls)
        assertEquals(1, sim.controls.observations.value.activeReservations)

        sim.controls.releaseHeld()
        runCurrent()
        assertIs<CreateObservation.Created>(call.await())

        sim.controls.enqueueLookup(LookupBehavior.Hold)
        val lookup = async { sim.reservation.lookup(RequestId("q1")) }
        runCurrent()
        assertEquals(1, sim.controls.observations.value.heldCalls)
        lookup.cancel()
        runCurrent()
        assertEquals(0, sim.controls.observations.value.heldCalls)
    }

    @Test
    fun `リセットは記録と台本と回数を消す`() = runTest {
        val sim = createSimulator(mapOf(session to 2))
        sim.reservation.create(RequestId("q1"), session, people)
        sim.controls.enqueueCreate(CreateBehavior.SoldOut)
        sim.controls.reset()
        assertEquals(SimulatorObservations(), sim.controls.observations.value)
    }
}
