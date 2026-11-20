package example.reservation.core

import example.eligibility.core.EligibilityAuthority
import example.eligibility.core.EligibilityCertificate
import example.eligibility.core.EligibilityOutcome
import example.eligibility.core.EligibilityPort
import example.eligibility.core.EligibilityRequestAcceptance
import example.eligibility.core.EligibilityStatus
import example.eligibility.core.QualificationKind
import example.eligibility.core.QualificationObservation
import example.eligibility.core.Reverification
import example.eligibility.core.ReverificationFailure
import example.eligibility.core.createEligibilityCore
import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val HOUR = 3_600_000L
private const val START = 100 * HOUR
private val pottery = QualificationKind("pottery-advanced")
private val roasting = QualificationKind("roasting-advanced")
private val open = Workshop(SessionId("open"), "通常回", EpochMillis(START), 4, NewBookingRequirement.NotRequired)
private val advanced = Workshop(SessionId("adv-pottery"), "上級ろくろ", EpochMillis(START), 4, NewBookingRequirement.Required(pottery))
private val advancedRoasting = Workshop(SessionId("adv-roast"), "上級焙煎", EpochMillis(START), 4, NewBookingRequirement.Required(roasting))
private val alice = ParticipantId("alice")
private val bob = ParticipantId("bob")
private val carol = ParticipantId("carol")
private val catalog = Catalog(
    listOf(open, advanced, advancedRoasting),
    listOf(Participant(alice, "アリス"), Participant(bob, "ボブ"), Participant(carol, "キャロル")),
)

private class QualificationService : EligibilityPort {
    val holdings = mutableMapOf<Pair<ParticipantId, QualificationKind>, EpochMillis>()
    var fetches = 0
    var unavailable = false
    var onFetch: suspend (ParticipantId, Int) -> Unit = { _, _ -> }

    override suspend fun fetch(participant: ParticipantId, kind: QualificationKind): QualificationObservation {
        fetches++
        onFetch(participant, fetches)
        if (unavailable) return QualificationObservation.Unavailable
        val expiresAt = holdings[participant to kind] ?: return QualificationObservation.NotHeld
        return QualificationObservation.Held(expiresAt)
    }
}

private class QualifiedPort : ReservationPort {
    var creates = 0
    var usages = 0
    var created = 0
    var cancelResult: CancelObservation = CancelObservation.Cancelled
    var usageResult: UsageObservation = UsageObservation.Active
    var createResult: CreateObservation? = null
    var sentParticipants: List<ParticipantId> = emptyList()

    override suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
        creates++
        sentParticipants = participants.toList()
        return createResult ?: CreateObservation.Created(ReservationId("r-${++created}"))
    }

    override suspend fun lookup(request: RequestId): LookupObservation = LookupObservation.NotFound

    override suspend fun cancel(reservation: ReservationId): CancelObservation = cancelResult

    override suspend fun usage(reservation: ReservationId): UsageObservation {
        usages++
        return usageResult
    }
}

private class QFixture(
    private val scope: TestScope,
    var now: Long = START - 2 * HOUR,
    authority: (EligibilityAuthority) -> EligibilityAuthority = { it },
) {
    val service = QualificationService()
    val port = QualifiedPort()
    private val coroutines = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
    private val clock = Clock { EpochMillis(now) }
    val eligibility = createEligibilityCore(service, clock, coroutines, 30.seconds)
    private var counter = 0
    val core = createReservationCore(
        catalog, port, authority(eligibility), clock, coroutines, { RequestId("req-${++counter}") }, 30.seconds,
    )
    val snapshot get() = core.snapshot.value

    fun select(workshop: Workshop, who: List<ParticipantId>) {
        core.selectWorkshop(workshop.id)
        who.forEach { core.toggleParticipant(it) }
    }

    fun verifyEligibility() {
        core.requestEligibility()
        scope.runCurrent()
    }

    fun confirmQualified(workshop: Workshop = advanced, who: List<ParticipantId> = listOf(alice, bob)): ConfirmedDraft.Qualified {
        select(workshop, who)
        verifyEligibility()
        return (core.confirmQualified() as ConfirmOutcome.Confirmed).draft as ConfirmedDraft.Qualified
    }

    fun grant(who: ParticipantId, kind: QualificationKind = pottery, until: Long = START + 10 * HOUR) {
        service.holdings[who to kind] = EpochMillis(until)
    }

    fun reserve(workshop: Workshop = advanced, who: List<ParticipantId> = listOf(alice, bob)): ReservationRecord.Reserved {
        core.submit(confirmQualified(workshop, who))
        scope.runCurrent()
        return snapshot.reservations.last() as ReservationRecord.Reserved
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
class QualifiedReservationTest {
    @Test
    fun `通常回は資格を確認せずに予約でき、資格の照会は行わない`() = runTest {
        val f = QFixture(this)
        f.select(open, listOf(alice))
        val confirmed = (f.core.confirmWithoutQualification() as ConfirmOutcome.Confirmed).draft
        assertIs<ConfirmedDraft.Open>(confirmed)
        f.core.submit(confirmed)
        runCurrent()
        assertEquals(1, f.port.creates)
        assertEquals(0, f.service.fetches)
        assertEquals(BookedBasis.WithoutQualification, f.snapshot.reservations.single().booking.basis)
    }

    @Test
    fun `通常回の入口を要資格回に使うことも、要資格の入口を通常回に使うこともできない`() = runTest {
        val f = QFixture(this)
        f.select(advanced, listOf(alice))
        assertEquals(ConfirmOutcome.QualificationRequired, f.core.confirmWithoutQualification())
        f.select(open, listOf(alice))
        assertEquals(ConfirmOutcome.QualificationNotRequired, f.core.confirmQualified())
        assertEquals(Acceptance.Rejected(Rejection.QualificationNotRequired), f.core.requestEligibility())
    }

    @Test
    fun `要資格回は全参加者の資格が有効なら予約でき、作成要求は一度だけ送る`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val record = f.reserve()
        assertEquals(1, f.port.creates)
        assertEquals(BookedBasis.WithQualification(pottery), record.booking.basis)
        assertEquals(4, f.service.fetches)
    }

    @Test
    fun `一人でも未取得・期限切れ・結果不明なら確認済みにならず、作成要求を送らない`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.select(advanced, listOf(alice, bob))
        f.verifyEligibility()
        assertEquals(listOf(bob), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)

        f.grant(bob, until = f.now)
        f.verifyEligibility()
        assertEquals(listOf(bob), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)

        f.grant(bob)
        f.service.unavailable = true
        f.core.requestEligibility()
        runCurrent()
        assertEquals(listOf(bob), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `結果不明は資格有効と推測せず、再照会でそろっても予約作成は自動で始まらない`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.service.unavailable = true
        f.select(advanced, listOf(alice))
        f.verifyEligibility()
        assertIs<EligibilityStatus.Settled>(f.eligibility.status(alice, pottery)).also {
            assertEquals(EligibilityOutcome.Unknown, it.outcome)
        }
        assertIs<ConfirmOutcome.EligibilityNotMet>(f.core.confirmQualified())
        f.service.unavailable = false
        f.verifyEligibility()
        assertIs<ConfirmOutcome.Confirmed>(f.core.confirmQualified())
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `Aの確認をBへ流用した結果や同じ人の確認の重複は人数分とみなさない`() = runTest {
        var swap = false
        val f = QFixture(this) { real ->
            object : EligibilityAuthority by real {
                override fun status(participant: ParticipantId, kind: QualificationKind): EligibilityStatus =
                    if (swap && participant == alice) real.status(bob, kind) else real.status(participant, kind)
            }
        }
        f.grant(alice)
        f.grant(bob)
        f.select(advanced, listOf(alice, bob))
        f.verifyEligibility()
        swap = true
        assertEquals(listOf(alice), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)

        f.core.toggleParticipant(bob)
        assertEquals(listOf(alice), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `別の種別の確認結果は必要資格に対応しない`() = runTest {
        val f = QFixture(this)
        f.grant(alice, kind = pottery)
        f.select(advancedRoasting, listOf(alice))
        f.verifyEligibility()
        assertEquals(listOf(alice), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `確認後に参加者や開催回を変えると、古い確認では予約できず現在の条件の確認が必要`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val confirmed = f.confirmQualified()
        f.core.toggleParticipant(carol)
        assertEquals(Acceptance.Rejected(Rejection.Stale), f.core.submit(confirmed))
        assertEquals(listOf(carol), (f.core.confirmQualified() as ConfirmOutcome.EligibilityNotMet).participants)
        f.core.selectWorkshop(advancedRoasting.id)
        f.core.toggleParticipant(alice)
        assertIs<ConfirmOutcome.EligibilityNotMet>(f.core.confirmQualified())
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `確認後で送信前に有効期限を迎えると、再確認で無効を判定して作成要求を送らない`() = runTest {
        val f = QFixture(this)
        f.grant(alice, until = START - HOUR)
        f.grant(bob, until = START - HOUR)
        val confirmed = f.confirmQualified()
        f.now = START - HOUR
        assertEquals(Acceptance.Accepted, f.core.submit(confirmed))
        runCurrent()
        assertEquals(ReverificationFailure.Expired, assertIs<ActiveRequest.EligibilityLost>(f.snapshot.request).reason)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `再確認の途中で一人目の資格が期限切れになると、全観測の後の共通の時刻で拒否して作成しない`() = runTest {
        val f = QFixture(this)
        f.grant(alice, until = START - HOUR)
        f.grant(bob)
        val confirmed = f.confirmQualified()
        f.service.onFetch = { who, _ -> if (who == bob && f.service.fetches > 2) f.now = START - HOUR }
        f.core.submit(confirmed)
        runCurrent()
        assertEquals(ReverificationFailure.Expired, assertIs<ActiveRequest.EligibilityLost>(f.snapshot.request).reason)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `発行後に付与が取り消されても、送信前の再確認が現在のサービスの観測で拒否する`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val confirmed = f.confirmQualified()
        f.service.holdings.remove(bob to pottery)
        f.core.submit(confirmed)
        runCurrent()
        assertEquals(ReverificationFailure.NotHeld, assertIs<ActiveRequest.EligibilityLost>(f.snapshot.request).reason)
        assertEquals(0, f.port.creates)

        f.verifyEligibility()
        val missing = assertIs<ConfirmOutcome.EligibilityNotMet>(f.core.confirmQualified())
        assertEquals(listOf(bob), missing.participants)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `再試行の資格確認中に受付が終了しても、元の未成立と再試行券を保持する`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        f.port.createResult = CreateObservation.NotCreated
        f.core.submit(f.confirmQualified())
        runCurrent()
        val notCreated = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)
        f.port.createResult = null
        f.service.onFetch = { _, _ -> f.now = START }
        assertEquals(Acceptance.Accepted, f.core.retry(notCreated.retry))
        runCurrent()
        val kept = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)
        assertSame(notCreated.retry, kept.retry)
        assertIs<CreateDenial.RegistrationClosed>(kept.lastDenial)
        assertEquals(1, f.port.creates)
    }

    @Test
    fun `再試行で資格を満たさないと作成要求を送らず、元の未成立は資格不成立の拒否と区別して残る`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        f.port.createResult = CreateObservation.NotCreated
        f.core.submit(f.confirmQualified())
        runCurrent()
        val notCreated = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)
        assertEquals(null, notCreated.lastDenial)
        f.port.createResult = null
        f.now = START - HOUR
        f.service.holdings[alice to pottery] = EpochMillis(START - HOUR)
        f.core.retry(notCreated.retry)
        runCurrent()
        val denied = assertIs<ActiveRequest.NotCreated>(f.snapshot.request)
        assertEquals(ReverificationFailure.Expired, assertIs<CreateDenial.EligibilityLost>(denied.lastDenial).reason)
        assertSame(notCreated.retry, denied.retry)
        assertEquals(1, f.port.creates)

        f.service.holdings[alice to pottery] = EpochMillis(START + HOUR)
        f.core.retry(denied.retry)
        runCurrent()
        assertEquals(2, f.port.creates)
        assertEquals(1, f.snapshot.reservations.size)
    }

    @Test
    fun `成立後に資格だけが失効しても予約は維持され、有効な予約の参加者の受付前提は有効のまま`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val record = f.reserve()
        f.now = START + 20 * HOUR
        f.service.holdings.clear()
        assertIs<ReservationRecord.Reserved>(f.snapshot.reservations.single())
        val lookup = f.core.admission(record.reservation, alice)
        val admission = assertIs<ReservationAdmission.Qualified>(assertIs<AdmissionLookup.Valid>(lookup).admission.record)
        assertEquals(pottery, admission.kind)
    }

    @Test
    fun `受付前提は成立が確認された予約に含まれる参加者だけで、結果不明の予約は有効にならない`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val record = f.reserve()
        assertEquals(AdmissionUnavailableReason.NotIncluded, assertIs<AdmissionLookup.Unavailable>(f.core.admission(record.reservation, carol)).reason)
        assertEquals(AdmissionUnavailableReason.NotReserved, assertIs<AdmissionLookup.Unavailable>(f.core.admission(ReservationId("other"), alice)).reason)
        assertEquals(0, f.port.usages)
    }

    @Test
    fun `取消の応答が失われても、サービスの現在の状態の照会で取消済みと判断する`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val record = f.reserve()
        f.port.cancelResult = CancelObservation.NoResponse
        f.core.cancel(record.cancel)
        runCurrent()
        assertIs<ReservationRecord.Reserved>(f.snapshot.reservations.single())
        f.port.usageResult = UsageObservation.Cancelled
        assertEquals(AdmissionUnavailableReason.Cancelled, assertIs<AdmissionLookup.Unavailable>(f.core.admission(record.reservation, alice)).reason)
        f.port.usageResult = UsageObservation.Failed
        assertEquals(AdmissionLookup.Unconfirmed, f.core.admission(record.reservation, alice))
    }

    @Test
    fun `確認済みの参加者集合は、利用側がキャストで変更しようとしても確認時の内容のまま送信される`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val confirmed = f.confirmQualified()
        @Suppress("UNCHECKED_CAST")
        val attempt = runCatching { (confirmed.participants as MutableList<ParticipantId>).add(carol) }
        assertTrue(attempt.isFailure)
        assertEquals(listOf(alice, bob), confirmed.participants)
        f.core.submit(confirmed)
        runCurrent()
        assertEquals(listOf(alice, bob), f.port.sentParticipants)
    }

    @Test
    fun `公開する状態の読み取り口は書き換えられず、成立記録と受付前提の一覧も変更できない`() = runTest {
        val f = QFixture(this)
        f.grant(alice)
        f.grant(bob)
        val record = f.reserve()
        assertNull(f.core.snapshot as? MutableStateFlow<*>)
        @Suppress("UNCHECKED_CAST")
        val clearRecords = runCatching { (f.snapshot.reservations as MutableList<ReservationRecord>).clear() }
        @Suppress("UNCHECKED_CAST")
        val clearAdmissions = runCatching { (record.admissions as MutableList<ReservationAdmission>).clear() }
        assertTrue(clearRecords.isFailure)
        assertTrue(clearAdmissions.isFailure)
        assertEquals(1, f.snapshot.reservations.size)
        assertEquals(2, (f.snapshot.reservations.single() as ReservationRecord.Reserved).admissions.size)
    }

    private fun QFixture.again(): ConfirmedDraft.Qualified {
        verifyEligibility()
        return (core.confirmQualified() as ConfirmOutcome.Confirmed).draft as ConfirmedDraft.Qualified
    }

    @Test
    fun `過去の再確認結果を再生する差し替えの資格実装は、今回の呼び出し区間に入らないので拒否する`() = runTest {
        var recorded: Reverification.Verified? = null
        var replay = false
        val f = QFixture(this) { real ->
            object : EligibilityAuthority by real {
                override suspend fun verify(certificates: List<EligibilityCertificate>): Reverification {
                    if (replay) return checkNotNull(recorded)
                    return real.verify(certificates).also { if (it is Reverification.Verified) recorded = it }
                }
            }
        }
        f.grant(alice)
        f.grant(bob)
        f.reserve()
        f.now += 1
        val second = f.again()
        replay = true
        f.core.submit(second)
        runCurrent()
        assertEquals(ReverificationFailure.Unconfirmed, assertIs<ActiveRequest.EligibilityLost>(f.snapshot.request).reason)
        assertEquals(1, f.port.creates)
    }

    @Test
    fun `判定時刻が揃わない証明書の組は、一つの判定として扱わず拒否する`() = runTest {
        lateinit var f: QFixture
        f = QFixture(this) { real ->
            object : EligibilityAuthority by real {
                override suspend fun verify(certificates: List<EligibilityCertificate>): Reverification {
                    val first = real.verify(certificates.filter { it.participant == alice })
                    f.now += 1
                    val second = real.verify(certificates.filter { it.participant == bob })
                    return Reverification.Verified(
                        (first as Reverification.Verified).certificates + (second as Reverification.Verified).certificates,
                    )
                }
            }
        }
        f.grant(alice)
        f.grant(bob)
        f.core.submit(f.confirmQualified())
        runCurrent()
        assertEquals(ReverificationFailure.Unconfirmed, assertIs<ActiveRequest.EligibilityLost>(f.snapshot.request).reason)
        assertEquals(0, f.port.creates)
    }

    @Test
    fun `判定時点の後に資格が失効しても、その判定は有効のまま送信し、期限の判定を予約は行わない`() = runTest {
        lateinit var f: QFixture
        f = QFixture(this) { real ->
            object : EligibilityAuthority by real {
                override suspend fun verify(certificates: List<EligibilityCertificate>): Reverification =
                    real.verify(certificates).also { f.now = START - HOUR + 1 }
            }
        }
        f.grant(alice, until = START - HOUR)
        f.grant(bob, until = START - HOUR)
        f.core.submit(f.confirmQualified())
        runCurrent()
        assertEquals(1, f.port.creates)
        assertIs<ReservationRecord.Reserved>(f.snapshot.reservations.single())
    }

    @Test
    fun `判定時点の後に送信期限を越えたときは、資格が有効でも作成要求を送らない`() = runTest {
        lateinit var f: QFixture
        f = QFixture(this) { real ->
            object : EligibilityAuthority by real {
                override suspend fun verify(certificates: List<EligibilityCertificate>): Reverification =
                    real.verify(certificates).also { f.now = START }
            }
        }
        f.grant(alice)
        f.grant(bob)
        f.core.submit(f.confirmQualified())
        runCurrent()
        assertIs<ActiveRequest.RegistrationClosed>(f.snapshot.request)
        assertEquals(0, f.port.creates)
    }
}
