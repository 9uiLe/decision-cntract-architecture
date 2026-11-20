package example.adapter.simulator

import example.checkin.core.CheckInAttemptId
import example.checkin.core.CheckInCreateObservation
import example.checkin.core.CheckInLookupObservation
import example.checkin.core.CheckInPort
import example.eligibility.core.EligibilityPort
import example.eligibility.core.QualificationKind
import example.eligibility.core.QualificationObservation
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.kernel.SessionId
import example.reservation.core.CancelObservation
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.RequestId
import example.reservation.core.ReservationId
import example.reservation.core.ReservationPort
import example.reservation.core.UsageObservation
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Simulator(
    val reservation: ReservationPort,
    val eligibility: EligibilityPort,
    val checkIn: CheckInPort,
    val controls: SimulatorControls,
)

fun createSimulator(
    capacities: Map<SessionId, Int>,
    holdings: List<QualificationHolding> = emptyList(),
): Simulator {
    val service = SimulatedService(capacities, holdings)
    return Simulator(
        reservation = service.reservationPort,
        eligibility = service.eligibilityPort,
        checkIn = service.checkInPort,
        controls = service.controls,
    )
}

private sealed interface RequestRecord {
    class Created(val reservation: ReservationId, val session: SessionId, val seats: Int) : RequestRecord
    data object NotCreated : RequestRecord
}

private sealed interface AttemptRecord {
    data object Recorded : AttemptRecord
    data object NotCreated : AttemptRecord
}

private data class HoldingKey(val participant: ParticipantId, val kindId: String)

// 三つのサービス役は一つの世界を共有するが、互いの判断を代行しない。受付サービス役は予約の有効性や受付時間を
// 判断せず、Core の要求を記録として受けるだけにする。
private class SimulatedService(
    private val capacities: Map<SessionId, Int>,
    private val initialHoldings: List<QualificationHolding>,
) {
    private val lock = SynchronizedObject()
    private val requests = LinkedHashMap<RequestId, RequestRecord>()
    private val cancelled = mutableSetOf<ReservationId>()
    private val holdings = LinkedHashMap<HoldingKey, EpochMillis>()
    private val attempts = LinkedHashMap<CheckInAttemptId, AttemptRecord>()
    private val recordedTargets = LinkedHashMap<Pair<ReservationId, ParticipantId>, Int>()
    private val createScript = ArrayDeque<CreateBehavior>()
    private val lookupScript = ArrayDeque<LookupBehavior>()
    private val cancelScript = ArrayDeque<CancelBehavior>()
    private val usageScript = ArrayDeque<UsageBehavior>()
    private val qualificationScript = ArrayDeque<QualificationBehavior>()
    private val checkInCreateScript = ArrayDeque<CheckInBehavior>()
    private val checkInLookupScript = ArrayDeque<LookupBehavior>()
    private val held = mutableListOf<CompletableDeferred<Unit>>()
    private var nextReservation = 0
    private var createRequests = 0
    private var lookupRequests = 0
    private var cancelRequests = 0
    private var usageRequests = 0
    private var qualificationRequests = 0
    private var checkInCreateRequests = 0
    private var checkInLookupRequests = 0
    private var checkInRepeated = 0
    private val observed = MutableStateFlow(SimulatorObservations())

    init {
        resetHoldings()
    }

    val controls: SimulatorControls = object : SimulatorControls {
        override val observations: StateFlow<SimulatorObservations> get() = observed

        override fun enqueueCreate(behavior: CreateBehavior) = update { createScript.addLast(behavior) }

        override fun enqueueLookup(behavior: LookupBehavior) = update { lookupScript.addLast(behavior) }

        override fun enqueueCancel(behavior: CancelBehavior) = update { cancelScript.addLast(behavior) }

        override fun enqueueUsage(behavior: UsageBehavior) = update { usageScript.addLast(behavior) }

        override fun enqueueQualification(behavior: QualificationBehavior) = update { qualificationScript.addLast(behavior) }

        override fun enqueueCheckInCreate(behavior: CheckInBehavior) = update { checkInCreateScript.addLast(behavior) }

        override fun enqueueCheckInLookup(behavior: LookupBehavior) = update { checkInLookupScript.addLast(behavior) }

        override fun grantQualification(participant: ParticipantId, kind: QualificationKind, expiresAt: EpochMillis) {
            require(ServiceCodes.codeFor(kind) != null) { "資格サービスの対象にない種別です: ${kind.id}" }
            update { holdings[HoldingKey(participant, kind.id)] = expiresAt }
        }

        override fun revokeQualification(participant: ParticipantId, kind: QualificationKind) =
            update { holdings.remove(HoldingKey(participant, kind.id)) }

        override fun releaseHeld() {
            val releasing = synchronized(lock) { held.toList().also { held.clear() }.also { publish() } }
            releasing.forEach { it.complete(Unit) }
        }

        override fun reset() {
            val releasing = synchronized(lock) {
                requests.clear()
                cancelled.clear()
                attempts.clear()
                recordedTargets.clear()
                createScript.clear()
                lookupScript.clear()
                cancelScript.clear()
                usageScript.clear()
                qualificationScript.clear()
                checkInCreateScript.clear()
                checkInLookupScript.clear()
                resetHoldings()
                nextReservation = 0
                createRequests = 0
                lookupRequests = 0
                cancelRequests = 0
                usageRequests = 0
                qualificationRequests = 0
                checkInCreateRequests = 0
                checkInLookupRequests = 0
                checkInRepeated = 0
                held.toList().also { held.clear() }.also { publish() }
            }
            releasing.forEach { it.complete(Unit) }
        }
    }

    val reservationPort: ReservationPort = object : ReservationPort {
        override suspend fun create(request: RequestId, session: SessionId, participants: List<ParticipantId>): CreateObservation {
            val (behavior, response) = synchronized(lock) {
                createRequests++
                val behavior = createScript.removeFirstOrNull() ?: CreateBehavior.Succeed
                val response = applyCreate(request, session, participants.size, behavior.effect)
                publish()
                behavior to response
            }
            return when (behavior.delivery) {
                Delivery.Respond -> response
                Delivery.Lose -> CreateObservation.NoResponse
                Delivery.Hold -> awaitRelease().let { response }
            }
        }

        override suspend fun lookup(request: RequestId): LookupObservation {
            val behavior = synchronized(lock) {
                lookupRequests++
                (lookupScript.removeFirstOrNull() ?: LookupBehavior.Normal).also { publish() }
            }
            return when (behavior) {
                LookupBehavior.Normal -> synchronized(lock) { observeRequest(request) }
                LookupBehavior.NotFound -> LookupObservation.NotFound
                LookupBehavior.Fail -> LookupObservation.Failed
                LookupBehavior.Hold -> awaitRelease().let { synchronized(lock) { observeRequest(request) } }
            }
        }

        override suspend fun cancel(reservation: ReservationId): CancelObservation {
            val behavior = synchronized(lock) {
                cancelRequests++
                (cancelScript.removeFirstOrNull() ?: CancelBehavior.Normal).also { publish() }
            }
            return when (behavior) {
                CancelBehavior.Normal -> applyCancel(reservation)
                CancelBehavior.Decline -> CancelObservation.Declined
                CancelBehavior.Lose -> applyCancel(reservation).let { CancelObservation.NoResponse }
                CancelBehavior.Hold -> awaitRelease().let { applyCancel(reservation) }
            }
        }

        override suspend fun usage(reservation: ReservationId): UsageObservation {
            val behavior = synchronized(lock) {
                usageRequests++
                (usageScript.removeFirstOrNull() ?: UsageBehavior.Normal).also { publish() }
            }
            return when (behavior) {
                UsageBehavior.Normal -> synchronized(lock) { observeUsage(reservation) }
                UsageBehavior.Fail -> UsageObservation.Failed
                UsageBehavior.Hold -> awaitRelease().let { synchronized(lock) { observeUsage(reservation) } }
            }
        }
    }

    val eligibilityPort: EligibilityPort = object : EligibilityPort {
        override suspend fun fetch(participant: ParticipantId, kind: QualificationKind): QualificationObservation {
            val behavior = synchronized(lock) {
                qualificationRequests++
                (qualificationScript.removeFirstOrNull() ?: QualificationBehavior.Normal).also { publish() }
            }
            return when (behavior) {
                QualificationBehavior.Normal -> decode(synchronized(lock) { wireFor(participant, kind) }, kind)
                QualificationBehavior.NoResponse -> QualificationObservation.Unavailable
                QualificationBehavior.Unrecognized -> decode("unrecognized-response", kind)
                QualificationBehavior.Hold ->
                    awaitRelease().let { decode(synchronized(lock) { wireFor(participant, kind) }, kind) }
            }
        }
    }

    val checkInPort: CheckInPort = object : CheckInPort {
        override suspend fun create(
            attempt: CheckInAttemptId,
            reservation: ReservationId,
            participant: ParticipantId,
        ): CheckInCreateObservation {
            val (behavior, response) = synchronized(lock) {
                checkInCreateRequests++
                val behavior = checkInCreateScript.removeFirstOrNull() ?: CheckInBehavior.Succeed
                val response = applyCheckIn(attempt, reservation to participant, behavior.effect)
                publish()
                behavior to response
            }
            return when (behavior.delivery) {
                Delivery.Respond -> response
                Delivery.Lose -> CheckInCreateObservation.NoResponse
                Delivery.Hold -> awaitRelease().let { response }
            }
        }

        override suspend fun lookup(attempt: CheckInAttemptId): CheckInLookupObservation {
            val behavior = synchronized(lock) {
                checkInLookupRequests++
                (checkInLookupScript.removeFirstOrNull() ?: LookupBehavior.Normal).also { publish() }
            }
            return when (behavior) {
                LookupBehavior.Normal -> synchronized(lock) { observeAttempt(attempt) }
                LookupBehavior.NotFound -> CheckInLookupObservation.NotFound
                LookupBehavior.Fail -> CheckInLookupObservation.Failed
                LookupBehavior.Hold -> awaitRelease().let { synchronized(lock) { observeAttempt(attempt) } }
            }
        }
    }

    private fun resetHoldings() {
        holdings.clear()
        initialHoldings.forEach { holdings[HoldingKey(it.participant, it.kind.id)] = it.expiresAt }
    }

    private fun wireFor(participant: ParticipantId, kind: QualificationKind): String {
        val expiresAt = holdings[HoldingKey(participant, kind.id)]
        val wire = if (expiresAt == null) QualificationWire(held = false, code = null, validUntil = null) else heldWire(kind, expiresAt)
        return QualificationWireFormat.render(wire)
    }

    private fun decode(text: String, kind: QualificationKind): QualificationObservation =
        QualificationDecoder.decode(text, kind)

    private fun applyCheckIn(
        attempt: CheckInAttemptId,
        target: Pair<ReservationId, ParticipantId>,
        effect: CheckInEffect,
    ): CheckInCreateObservation {
        attempts[attempt]?.let {
            return when (it) {
                AttemptRecord.Recorded -> CheckInCreateObservation.Recorded
                AttemptRecord.NotCreated -> CheckInCreateObservation.NotCreated
            }
        }
        if (effect == CheckInEffect.Reject) {
            attempts[attempt] = AttemptRecord.NotCreated
            return CheckInCreateObservation.NotCreated
        }
        if (target in recordedTargets) {
            checkInRepeated++
            return CheckInCreateObservation.AlreadyRecorded
        }
        attempts[attempt] = AttemptRecord.Recorded
        recordedTargets[target] = (recordedTargets[target] ?: 0) + 1
        return CheckInCreateObservation.Recorded
    }

    private fun observeAttempt(attempt: CheckInAttemptId): CheckInLookupObservation = when (attempts[attempt]) {
        AttemptRecord.Recorded -> CheckInLookupObservation.Found
        AttemptRecord.NotCreated -> CheckInLookupObservation.NotCreated
        null -> CheckInLookupObservation.NotFound
    }

    private fun applyCreate(request: RequestId, session: SessionId, seats: Int, effect: CreateEffect): CreateObservation =
        when (effect) {
            CreateEffect.Reject -> {
                requests[request] = RequestRecord.NotCreated
                CreateObservation.NotCreated
            }
            CreateEffect.SoldOut -> {
                requests[request] = RequestRecord.NotCreated
                CreateObservation.Full
            }
            CreateEffect.Create -> if (remainingSeats(session) < seats) {
                requests[request] = RequestRecord.NotCreated
                CreateObservation.Full
            } else {
                val id = ReservationId("r-${++nextReservation}")
                requests[request] = RequestRecord.Created(id, session, seats)
                CreateObservation.Created(id)
            }
        }

    private fun remainingSeats(session: SessionId): Int {
        val used = requests.values.filterIsInstance<RequestRecord.Created>()
            .filter { it.session == session && it.reservation !in cancelled }
            .sumOf { it.seats }
        return (capacities[session] ?: 0) - used
    }

    private fun observeRequest(request: RequestId): LookupObservation = when (val record = requests[request]) {
        is RequestRecord.Created -> LookupObservation.Found(record.reservation)
        RequestRecord.NotCreated -> LookupObservation.NotCreated
        null -> LookupObservation.NotFound
    }

    private fun observeUsage(reservation: ReservationId): UsageObservation = when {
        reservation in cancelled -> UsageObservation.Cancelled
        requests.values.any { it is RequestRecord.Created && it.reservation == reservation } -> UsageObservation.Active
        else -> UsageObservation.NotFound
    }

    private fun applyCancel(reservation: ReservationId): CancelObservation = synchronized(lock) {
        val exists = requests.values.any { it is RequestRecord.Created && it.reservation == reservation }
        if (exists && cancelled.add(reservation)) {
            publish()
            CancelObservation.Cancelled
        } else {
            CancelObservation.Declined
        }
    }

    // 保留中の呼び出しが呼び出し元によってキャンセルされても、保留の記録は残さない。
    private suspend fun awaitRelease() {
        val gate = CompletableDeferred<Unit>()
        synchronized(lock) { held += gate; publish() }
        try {
            gate.await()
        } finally {
            synchronized(lock) { held -= gate; publish() }
        }
    }

    private fun update(change: () -> Unit) = synchronized(lock) { change(); publish() }

    private fun publish() {
        observed.value = SimulatorObservations(
            createRequests = createRequests,
            lookupRequests = lookupRequests,
            cancelRequests = cancelRequests,
            usageRequests = usageRequests,
            qualificationRequests = qualificationRequests,
            checkInCreateRequests = checkInCreateRequests,
            checkInLookupRequests = checkInLookupRequests,
            checkInRepeatedForRecordedTarget = checkInRepeated,
            activeReservations = requests.values.count {
                it is RequestRecord.Created && it.reservation !in cancelled
            },
            checkInRecords = recordedTargets.map { (target, count) -> CheckInRecordCount(target.first, target.second, count) },
            heldCalls = held.size,
            queuedCreate = createScript.size,
            queuedLookup = lookupScript.size,
            queuedCancel = cancelScript.size,
            queuedUsage = usageScript.size,
            queuedQualification = qualificationScript.size,
            queuedCheckInCreate = checkInCreateScript.size,
            queuedCheckInLookup = checkInLookupScript.size,
        )
    }
}
