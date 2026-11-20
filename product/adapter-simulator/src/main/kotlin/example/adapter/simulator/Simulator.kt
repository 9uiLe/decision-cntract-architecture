package example.adapter.simulator

import example.reservation.ports.CancelObservation
import example.reservation.ports.CreateObservation
import example.reservation.ports.LookupObservation
import example.reservation.ports.ParticipantId
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationId
import example.reservation.ports.ReservationPort
import example.reservation.ports.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Simulator(val port: ReservationPort, val controls: SimulatorControls)

fun createSimulator(capacities: Map<SessionId, Int>): Simulator {
    val service = SimulatedService(capacities)
    return Simulator(port = service, controls = service.controls)
}

private sealed interface RequestRecord {
    class Created(val reservation: ReservationId, val session: SessionId, val seats: Int) : RequestRecord
    data object NotCreated : RequestRecord
}

private class SimulatedService(private val capacities: Map<SessionId, Int>) : ReservationPort {
    private val lock = Any()
    private val requests = LinkedHashMap<RequestId, RequestRecord>()
    private val cancelled = mutableSetOf<ReservationId>()
    private val createScript = ArrayDeque<CreateBehavior>()
    private val lookupScript = ArrayDeque<LookupBehavior>()
    private val cancelScript = ArrayDeque<CancelBehavior>()
    private val held = mutableListOf<CompletableDeferred<Unit>>()
    private var nextReservation = 0
    private var createRequests = 0
    private var lookupRequests = 0
    private var cancelRequests = 0
    private val observed = MutableStateFlow(SimulatorObservations())

    val controls: SimulatorControls = object : SimulatorControls {
        override val observations: StateFlow<SimulatorObservations> get() = observed

        override fun enqueueCreate(behavior: CreateBehavior) = update { createScript.addLast(behavior) }

        override fun enqueueLookup(behavior: LookupBehavior) = update { lookupScript.addLast(behavior) }

        override fun enqueueCancel(behavior: CancelBehavior) = update { cancelScript.addLast(behavior) }

        override fun releaseHeld() {
            val releasing = synchronized(lock) { held.toList().also { held.clear() }.also { publish() } }
            releasing.forEach { it.complete(Unit) }
        }

        override fun reset() {
            val releasing = synchronized(lock) {
                requests.clear()
                cancelled.clear()
                createScript.clear()
                lookupScript.clear()
                cancelScript.clear()
                nextReservation = 0
                createRequests = 0
                lookupRequests = 0
                cancelRequests = 0
                held.toList().also { held.clear() }.also { publish() }
            }
            releasing.forEach { it.complete(Unit) }
        }
    }

    override suspend fun create(
        request: RequestId,
        session: SessionId,
        participants: List<ParticipantId>,
    ): CreateObservation {
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
            activeReservations = requests.values.count {
                it is RequestRecord.Created && it.reservation !in cancelled
            },
            heldCalls = held.size,
            queuedCreate = createScript.size,
            queuedLookup = lookupScript.size,
            queuedCancel = cancelScript.size,
        )
    }
}
