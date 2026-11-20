package dca.tests

import dca.core.Clock
import dca.core.ConfirmOutcome
import dca.core.ConfirmedDraft
import dca.core.CreateObservation
import dca.core.EpochMillis
import dca.core.LookupObservation
import dca.core.Outcome
import dca.core.ParticipantId
import dca.core.Reservation
import dca.core.ReservationCore
import dca.core.ReservationPort
import dca.core.RequestId
import dca.core.SessionId
import dca.core.Workshop
import dca.ui.ScreenState
import dca.ui.project

private const val START = 1_000L

private class FakePort : ReservationPort {
    val createScript = ArrayDeque<CreateObservation>()
    val lookupScript = ArrayDeque<LookupObservation>()
    val createRequests = mutableListOf<RequestId>()
    val lookupRequests = mutableListOf<RequestId>()

    override fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
        createRequests += request
        return createScript.removeFirst()
    }

    override fun lookup(request: RequestId): LookupObservation {
        lookupRequests += request
        return lookupScript.removeFirst()
    }
}

private class Fixture(var now: Long) {
    val port = FakePort()
    private var counter = 0
    val core = ReservationCore(
        Workshop(SessionId("workshop"), EpochMillis(START)),
        port,
        Clock { EpochMillis(now) },
    ) { RequestId("req-${++counter}") }

    fun confirmed(): ConfirmedDraft =
        (core.confirm(listOf(ParticipantId("a"))) as ConfirmOutcome.Confirmed).draft
}

private fun scenario(name: String, body: () -> Unit) {
    body()
    println("ok: $name")
}

private val reservation = Reservation("r-1")

fun main() {
    scenario("開始時刻ちょうどは create を送らず、直前は送る") {
        val atStart = Fixture(START)
        check(atStart.core.submit(atStart.confirmed()) == Outcome.RegistrationClosed)
        check(atStart.port.createRequests.isEmpty())

        val before = Fixture(START - 1)
        before.port.createScript += CreateObservation.Created(reservation)
        check(before.core.submit(before.confirmed()) == Outcome.Reserved(reservation))
        check(before.port.createRequests.size == 1)
    }

    scenario("応答喪失は開始時刻を過ぎても元の RequestId で照会し、記録なし・照会失敗では不明を維持する") {
        val f = Fixture(START - 1)
        f.port.createScript += CreateObservation.NoResponse
        val unknown = f.core.submit(f.confirmed()) as Outcome.Unknown
        check(unknown.check.request == RequestId("req-1"))

        f.now = START + 10
        f.port.lookupScript += LookupObservation.NotFound
        f.port.lookupScript += LookupObservation.Failed
        f.port.lookupScript += LookupObservation.Found(reservation)
        check(f.core.checkStatus(unknown.check) == unknown)
        check(f.core.checkStatus(unknown.check) == unknown)
        check(f.core.checkStatus(unknown.check) == Outcome.Reserved(reservation))
        check(f.port.lookupRequests == List(3) { RequestId("req-1") })
        check(f.port.createRequests.size == 1)
    }

    scenario("未成立確定後の明示再試行は新しい RequestId を持ち、結果不明の照会はその ID を使う") {
        val f = Fixture(START - 1)
        f.port.createScript += CreateObservation.NotCreated
        f.port.createScript += CreateObservation.NoResponse
        val notCreated = f.core.submit(f.confirmed()) as Outcome.NotCreated
        check(f.port.createRequests.size == 1)

        val notCreatedScreen = project(notCreated) as ScreenState.NotCreated
        check(notCreatedScreen.retry === notCreated.retry)

        val unknown = f.core.retry(notCreated.retry) as Outcome.Unknown
        val unknownScreen = project(unknown) as ScreenState.Unknown
        check(unknownScreen.check === unknown.check)
        check(f.port.createRequests == listOf(RequestId("req-1"), RequestId("req-2")))
        check(unknown.check.request == RequestId("req-2"))

        f.port.lookupScript += LookupObservation.NotFound
        f.core.checkStatus(unknown.check)
        check(f.port.lookupRequests == listOf(RequestId("req-2")))

        check(f.core.retry(notCreated.retry) == Outcome.Stale)
        check(f.port.createRequests.size == 2)
        check(f.core.current == unknown)
    }

    scenario("同じ内容で再確認すると、古い確認では送れず、現在の確認はそのまま使える") {
        val f = Fixture(START - 1)
        val old = f.confirmed()
        val latest = f.confirmed()
        check(f.core.submit(old) == Outcome.Stale)
        check(f.port.createRequests.isEmpty())

        f.port.createScript += CreateObservation.Full
        check(f.core.submit(latest) == Outcome.SoldOut)
        check(f.core.submit(latest) == Outcome.Stale)
        check(f.port.createRequests.size == 1)
    }

    scenario("開始時刻以降の再試行は create を送らず、未成立の事実を保持する") {
        val f = Fixture(START - 1)
        f.port.createScript += CreateObservation.NotCreated
        val notCreated = f.core.submit(f.confirmed()) as Outcome.NotCreated

        f.now = START
        check(f.core.retry(notCreated.retry) == Outcome.RetryClosed(notCreated))
        check(f.port.createRequests.size == 1)
        check(f.core.current == notCreated)
    }

    scenario("結果不明のまま通常の submit や再確認へ進めない") {
        val f = Fixture(START - 1)
        f.port.createScript += CreateObservation.NoResponse
        val draft = f.confirmed()
        val unknown = f.core.submit(draft) as Outcome.Unknown

        check(f.core.submit(draft) == Outcome.Stale)
        check(f.core.confirm(listOf(ParticipantId("a"))) == ConfirmOutcome.AttemptAlreadyStarted)
        check(f.port.createRequests.size == 1)
        check(f.core.current == unknown)
    }

    scenario("照会で未成立が確定すると、再試行できる前提を得る") {
        val f = Fixture(START - 1)
        f.port.createScript += CreateObservation.NoResponse
        val unknown = f.core.submit(f.confirmed()) as Outcome.Unknown
        f.port.lookupScript += LookupObservation.NotCreated
        check(f.core.checkStatus(unknown.check) is Outcome.NotCreated)
    }

    scenario("予約確定後に同じ内容で再確認しても、新しい試行を始めず create も増えない") {
        val f = Fixture(START - 1)
        f.port.createScript += CreateObservation.Created(reservation)
        val reserved = f.core.submit(f.confirmed())
        check(reserved == Outcome.Reserved(reservation))

        check(f.core.confirm(listOf(ParticipantId("a"))) == ConfirmOutcome.AttemptAlreadyStarted)
        check(f.port.createRequests.size == 1)
        check(f.core.current == reserved)
    }

    scenario("確認の成立条件と画面への射影") {
        val f = Fixture(START - 1)
        check(f.core.confirm(emptyList()) == ConfirmOutcome.EmptyParticipants)
        check(f.core.confirm(listOf(ParticipantId("a"), ParticipantId("a"))) == ConfirmOutcome.DuplicateParticipants)
        check(project(Outcome.Stale) == ScreenState.Outdated)
        check(project(Outcome.Reserved(reservation)) == ScreenState.Reserved("r-1"))
    }
}
