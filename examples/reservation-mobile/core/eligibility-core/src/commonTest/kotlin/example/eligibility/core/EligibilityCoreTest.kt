package example.eligibility.core

import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val alice = ParticipantId("alice")
private val bob = ParticipantId("bob")
private val kind = QualificationKind("pottery-advanced")

private class Service : EligibilityPort {
    val observations = mutableMapOf<ParticipantId, QualificationObservation>()
    var fetches = 0
    var gate: CompletableDeferred<Unit>? = null
    var onFetch: (ParticipantId) -> Unit = {}

    override suspend fun fetch(participant: ParticipantId, kind: QualificationKind): QualificationObservation {
        fetches++
        onFetch(participant)
        gate?.await()
        return observations[participant] ?: QualificationObservation.NotHeld
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(scope: TestScope, var now: Long = 1_000) {
    val service = Service()
    val core = createEligibilityCore(
        service,
        Clock { EpochMillis(now) },
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)),
        30.seconds,
    )

    fun outcome(who: ParticipantId): EligibilityOutcome = assertIs<EligibilityStatus.Settled>(core.status(who, kind)).outcome
}

@OptIn(ExperimentalCoroutinesApi::class)
class EligibilityCoreTest {
    @Test
    fun `期限の直前は有効で、期限ちょうどから無効`() = runTest {
        val f = Fixture(this, now = 999)
        f.service.observations[alice] = QualificationObservation.Held(EpochMillis(1_000))
        f.core.request(alice, kind)
        runCurrent()
        assertIs<EligibilityOutcome.Eligible>(f.outcome(alice))
        f.now = 1_000
        assertEquals(EligibilityOutcome.Expired, f.outcome(alice))
    }

    @Test
    fun `期限切れ・未取得・結果不明を区別し、読めない応答を有効と推測しない`() = runTest {
        val f = Fixture(this)
        f.service.observations[alice] = QualificationObservation.Held(EpochMillis(1_000))
        f.service.observations[bob] = QualificationObservation.Unavailable
        f.core.request(alice, kind)
        f.core.request(bob, kind)
        runCurrent()
        assertEquals(EligibilityOutcome.Expired, f.outcome(alice))
        assertEquals(EligibilityOutcome.Unknown, f.outcome(bob))
        f.core.request(ParticipantId("carol"), kind)
        runCurrent()
        assertEquals(EligibilityOutcome.NotHeld, assertIs<EligibilityStatus.Settled>(f.core.status(ParticipantId("carol"), kind)).outcome)
    }

    @Test
    fun `確認中の同じ組の要求は重複して照会せず、別の組は並行して照会できる`() = runTest {
        val f = Fixture(this)
        f.service.gate = CompletableDeferred()
        assertEquals(EligibilityRequestAcceptance.Accepted, f.core.request(alice, kind))
        assertEquals(EligibilityRequestAcceptance.Rejected(EligibilityRequestRejection.Busy), f.core.request(alice, kind))
        assertEquals(EligibilityRequestAcceptance.Accepted, f.core.request(bob, kind))
        runCurrent()
        assertEquals(2, f.service.fetches)
        assertEquals(EligibilityStatus.Checking, f.core.status(alice, kind))
        f.service.gate?.complete(Unit)
        runCurrent()
        assertEquals(2, f.service.fetches)
    }

    @Test
    fun `発行した証明書は確認した時点を記録し、再確認で再発行すると新しい時点になる`() = runTest {
        val f = Fixture(this, now = 100)
        f.service.observations[alice] = QualificationObservation.Held(EpochMillis(10_000))
        f.core.request(alice, kind)
        runCurrent()
        val first = assertIs<EligibilityOutcome.Eligible>(f.outcome(alice)).certificate
        assertEquals(EpochMillis(100), first.checkedAt)
        f.now = 500
        val verified = assertIs<Reverification.Verified>(f.core.verify(listOf(first)))
        assertEquals(EpochMillis(500), verified.certificates.single().checkedAt)
    }

    @Test
    fun `再確認は全観測の後の共通の時刻で全員の期限を判定する`() = runTest {
        val f = Fixture(this, now = 100)
        f.service.observations[alice] = QualificationObservation.Held(EpochMillis(1_000))
        f.service.observations[bob] = QualificationObservation.Held(EpochMillis(1_000))
        f.core.request(alice, kind)
        f.core.request(bob, kind)
        runCurrent()
        val certificates = listOf(alice, bob).map { assertIs<EligibilityOutcome.Eligible>(f.outcome(it)).certificate }
        f.service.onFetch = { if (it == bob) f.now = 1_000 }
        assertEquals(ReverificationFailure.Expired, assertIs<Reverification.Failed>(f.core.verify(certificates)).reason)
    }

    @Test
    fun `再確認は現在の観測が未取得なら拒否し、観測できなければ確認不能とする`() = runTest {
        val f = Fixture(this, now = 100)
        f.service.observations[alice] = QualificationObservation.Held(EpochMillis(1_000))
        f.core.request(alice, kind)
        runCurrent()
        val certificate = assertIs<EligibilityOutcome.Eligible>(f.outcome(alice)).certificate
        f.service.observations[alice] = QualificationObservation.NotHeld
        assertEquals(ReverificationFailure.NotHeld, assertIs<Reverification.Failed>(f.core.verify(listOf(certificate))).reason)
        f.service.observations[alice] = QualificationObservation.Unavailable
        assertEquals(ReverificationFailure.Unconfirmed, assertIs<Reverification.Failed>(f.core.verify(listOf(certificate))).reason)
        assertEquals(ReverificationFailure.TargetMismatch, assertIs<Reverification.Failed>(f.core.verify(listOf(certificate, certificate))).reason)
    }

    @Test
    fun `再確認が返す証明書は全員が同じ判定時刻で、公開する読み取り口と証明書の一覧は書き換えられない`() = runTest {
        val f = Fixture(this)
        f.service.observations[alice] = QualificationObservation.Held(EpochMillis(5_000))
        f.service.observations[bob] = QualificationObservation.Held(EpochMillis(5_000))
        f.core.request(alice, kind)
        f.core.request(bob, kind)
        runCurrent()
        val issued = listOf(alice, bob).map { assertIs<EligibilityOutcome.Eligible>(f.outcome(it)).certificate }
        f.now = 2_000
        val verified = assertIs<Reverification.Verified>(f.core.verify(issued))
        assertEquals(setOf(EpochMillis(2_000)), verified.certificates.map { it.checkedAt }.toSet())
        @Suppress("UNCHECKED_CAST")
        val attempt = runCatching { (verified.certificates as MutableList<EligibilityCertificate>).clear() }
        assertTrue(attempt.isFailure)
        assertEquals(2, verified.certificates.size)
        assertNull(f.core.snapshot as? MutableStateFlow<*>)
    }
}
