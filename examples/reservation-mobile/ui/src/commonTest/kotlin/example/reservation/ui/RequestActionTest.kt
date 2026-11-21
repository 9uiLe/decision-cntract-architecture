package example.reservation.ui

import example.eligibility.core.ReverificationFailure
import example.kernel.EpochMillis
import example.reservation.core.Acceptance
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.RequestId
import example.reservation.core.Rejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

private val summary = BookingSummary("陶芸", EpochMillis(ACTION_START), listOf("アリス"))

class RequestActionTest {
    @Test
    fun `照会と再試行の案内だけが操作になり、ほかのカードは操作を持たない`() = runTest {
        val s = ActionSession(this)
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NoResponse)
        val unknown = s.unknown
        s.lookUp(endpoint, LookupObservation.NotCreated)
        val notCreated = s.notCreated
        val cards = listOf(
            RequestCard.Sending(summary),
            RequestCard.CheckingStatus(summary),
            unknown,
            notCreated,
            RequestCard.SoldOut(summary),
            RequestCard.RegistrationClosed(summary),
            RequestCard.EligibilityLost(summary, ReverificationFailure.Expired),
        )
        val actions = cards.mapNotNull { card ->
            when (card) {
                is RequestCard.Unknown -> card.boundTo(endpoint)
                is RequestCard.NotCreated -> card.boundTo(endpoint)
                is RequestCard.Sending, is RequestCard.CheckingStatus, is RequestCard.SoldOut,
                is RequestCard.RegistrationClosed, is RequestCard.EligibilityLost -> null
            }
        }
        assertEquals(listOf(RequestAction.CheckStatus::class, RequestAction.Retry::class), actions.map { it::class })
    }

    @Test
    fun `操作は実行先と券を保持し、同じ実行先と同じ券だけが等しい`() = runTest {
        val s = ActionSession(this)
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NoResponse)
        val first = s.unknown
        s.lookUp(endpoint, LookupObservation.Failed)
        val second = s.unknown

        val action = first.boundTo(endpoint)
        assertSame(endpoint, action.endpoint)
        assertSame(first.check, action.ticket)
        assertEquals(action, first.boundTo(endpoint))
        assertEquals(action.hashCode(), first.boundTo(endpoint).hashCode())
        assertEquals(first.summary, second.summary)
        assertNotEquals(action, second.boundTo(endpoint), "表示が同じでも、券が違えば別の操作")
        assertNotEquals(action, first.boundTo(s.endpoint()), "券が同じでも、実行先が違えば別の操作")
    }

    @Test
    fun `実行は保持した券で Core の照会を一度だけ出し、古い操作は拒否されて要求を出さない`() = runTest {
        val s = ActionSession(this)
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NoResponse)
        val old = s.unknown.boundTo(endpoint)
        s.lookUp(endpoint, LookupObservation.Failed)
        val current = s.unknown.boundTo(endpoint)
        assertEquals(listOf(RequestId("R1")), s.port.lookupCalls)

        assertEquals(Acceptance.Rejected(Rejection.Stale), old.execute())
        s.settle()
        assertEquals(listOf(RequestId("R1")), s.port.lookupCalls)
        assertEquals(1, s.port.createCalls.size)

        assertEquals(Acceptance.Accepted, current.execute())
        s.settle()
        assertEquals(listOf(RequestId("R1"), RequestId("R1")), s.port.lookupCalls)
    }

    @Test
    fun `未成立の再試行も保持した券で一度だけ作成を出し、古い操作は拒否される`() = runTest {
        val s = ActionSession(this)
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NotCreated)
        val old = s.notCreated.boundTo(endpoint)
        s.retry(endpoint, CreateObservation.NotCreated)
        val current = s.notCreated.boundTo(endpoint)
        assertEquals(2, s.port.createCalls.size)

        assertEquals(Acceptance.Rejected(Rejection.Stale), old.execute())
        s.settle()
        assertEquals(2, s.port.createCalls.size)

        assertEquals(Acceptance.Accepted, current.execute())
        s.settle()
        assertEquals(3, s.port.createCalls.size)
    }
}
