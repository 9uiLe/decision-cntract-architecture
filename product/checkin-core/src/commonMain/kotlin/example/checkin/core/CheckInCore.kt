package example.checkin.core

import example.kernel.Clock
import example.kernel.EpochMillis
import example.reservation.core.AdmissionLookup
import example.reservation.core.AdmissionSource
import example.reservation.core.ReservationAdmission
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

private const val OPENS_BEFORE_START_MILLIS = 30L * 60 * 1000
private const val CLOSES_AFTER_START_MILLIS = 15L * 60 * 1000

// 受付時間と受付済みの状態は、ここだけが判断する。予約が現在も有効かは予約 Core の確認結果に従い、
// 結果は理由を保ったまま受付の拒否として画面へ渡す。参加者ごとに独立して進行し、受付の記録は参加者単位で一件。
class CheckInCore internal constructor(
    private val source: AdmissionSource,
    private val port: CheckInPort,
    private val clock: Clock,
    private val newAttemptId: () -> CheckInAttemptId,
    private val scope: CoroutineScope,
    private val operationTimeout: Duration,
) {
    private val lock = SynchronizedObject()
    private var lastOperation = 0L
    private val entries = LinkedHashMap<CheckInKey, Entry>()
    private val state = MutableStateFlow(CheckInSnapshot(emptyMap()))

    val snapshot: StateFlow<CheckInSnapshot> get() = state

    fun checkIn(admission: ReservationAdmission): CheckInAcceptance {
        val key = CheckInKey(admission.reservation, admission.participant)
        return begin(key, admission) { current ->
            when (current) {
                null, is ParticipantCheckIn.NotAdmitted -> null
                is ParticipantCheckIn.Processing -> CheckInRejection.Busy
                ParticipantCheckIn.CheckedIn -> CheckInRejection.AlreadyCheckedIn
                is ParticipantCheckIn.Unknown, is ParticipantCheckIn.NotCreated -> CheckInRejection.Unresolved
            }
        }
    }

    fun retry(ticket: CheckInRetryTicket): CheckInAcceptance {
        val admission = ticket.admission
        val key = CheckInKey(admission.reservation, admission.participant)
        return begin(key, admission) { current ->
            when (current) {
                is ParticipantCheckIn.NotCreated -> if (current.retry === ticket) null else CheckInRejection.Stale
                is ParticipantCheckIn.Processing -> CheckInRejection.Busy
                null, ParticipantCheckIn.CheckedIn, is ParticipantCheckIn.Unknown, is ParticipantCheckIn.NotAdmitted ->
                    CheckInRejection.Stale
            }
        }
    }

    // 結果不明の間は元の試行を照会する。新しい受付は作らず、受付時間が過ぎた後も続けられる。
    fun checkStatus(ticket: CheckInStatusTicket): CheckInAcceptance {
        val admission = ticket.admission
        val key = CheckInKey(admission.reservation, admission.participant)
        val operation = synchronized(lock) {
            val current = entries[key]?.status
            if (current !is ParticipantCheckIn.Unknown || current.check !== ticket) {
                val duplicate = current is ParticipantCheckIn.Processing
                return CheckInAcceptance.Rejected(if (duplicate) CheckInRejection.Busy else CheckInRejection.Stale)
            }
            val operation = ++lastOperation
            put(key, operation, ParticipantCheckIn.Processing(ProcessingStage.LookingUp))
            operation
        }
        scope.launch {
            val next: ParticipantCheckIn = when (observe(CheckInLookupObservation.Failed) { port.lookup(ticket.attempt) }) {
                CheckInLookupObservation.Found -> ParticipantCheckIn.CheckedIn
                CheckInLookupObservation.NotCreated -> ParticipantCheckIn.NotCreated(CheckInRetryTicket(admission))
                CheckInLookupObservation.NotFound, CheckInLookupObservation.Failed ->
                    ParticipantCheckIn.Unknown(CheckInStatusTicket(ticket.attempt, admission))
            }
            settle(key, operation, next)
        }
        return CheckInAcceptance.Accepted
    }

    private inline fun begin(
        key: CheckInKey,
        admission: ReservationAdmission,
        precondition: (ParticipantCheckIn?) -> CheckInRejection?,
    ): CheckInAcceptance {
        val operation = synchronized(lock) {
            precondition(entries[key]?.status)?.let { return CheckInAcceptance.Rejected(it) }
            when (windowAt(admission.start)) {
                Window.NotYetOpen -> return CheckInAcceptance.Rejected(CheckInRejection.NotYetOpen)
                Window.Closed -> return CheckInAcceptance.Rejected(CheckInRejection.Closed)
                Window.Open -> Unit
            }
            val operation = ++lastOperation
            put(key, operation, ParticipantCheckIn.Processing(ProcessingStage.ConfirmingReservation))
            operation
        }
        scope.launch { proceed(key, operation, admission) }
        return CheckInAcceptance.Accepted
    }

    // 処理中を確保した後で予約の現在の状態を確かめる。戻ったら、対象と時刻を取り直してから受付を要求する。
    private suspend fun proceed(key: CheckInKey, operation: Long, requested: ReservationAdmission) {
        val lookup = try {
            source.admission(key.reservation, key.participant)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            AdmissionLookup.Unconfirmed
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            AdmissionLookup.Unconfirmed
        }
        val admission = when (lookup) {
            is AdmissionLookup.Valid -> lookup.admission
            is AdmissionLookup.Unavailable ->
                return deny(key, operation, requested, Denial.ReservationUnavailable(lookup.reason))
            AdmissionLookup.Unconfirmed -> return deny(key, operation, requested, Denial.ReservationUnconfirmed)
        }
        if (!admission.isFor(key, requested)) return deny(key, operation, requested, Denial.WrongTarget)
        when (windowAt(admission.start)) {
            Window.NotYetOpen -> return deny(key, operation, admission, Denial.NotYetOpen)
            Window.Closed -> return deny(key, operation, admission, Denial.Closed)
            Window.Open -> Unit
        }
        if (!advance(key, operation, ProcessingStage.Recording)) return
        val attempt = newAttemptId()
        val next: ParticipantCheckIn = when (
            observe(CheckInCreateObservation.NoResponse) { port.create(attempt, key.reservation, key.participant) }
        ) {
            CheckInCreateObservation.Recorded, CheckInCreateObservation.AlreadyRecorded -> ParticipantCheckIn.CheckedIn
            CheckInCreateObservation.NotCreated -> ParticipantCheckIn.NotCreated(CheckInRetryTicket(admission))
            CheckInCreateObservation.NoResponse -> ParticipantCheckIn.Unknown(CheckInStatusTicket(attempt, admission))
        }
        settle(key, operation, next)
    }

    private fun ReservationAdmission.isFor(key: CheckInKey, requested: ReservationAdmission): Boolean =
        reservation == key.reservation && participant == key.participant && session == requested.session

    private fun deny(key: CheckInKey, operation: Long, admission: ReservationAdmission, denial: Denial) =
        settle(key, operation, ParticipantCheckIn.NotAdmitted(denial, admission))

    // 受付開始は含み、受付終了は含まない。
    private fun windowAt(start: EpochMillis): Window {
        val now = clock.now().value
        return when {
            now < start.value - OPENS_BEFORE_START_MILLIS -> Window.NotYetOpen
            now >= start.value + CLOSES_AFTER_START_MILLIS -> Window.Closed
            else -> Window.Open
        }
    }

    private enum class Window { NotYetOpen, Open, Closed }

    private fun advance(key: CheckInKey, operation: Long, stage: ProcessingStage): Boolean = synchronized(lock) {
        if (entries[key]?.operation != operation) return@synchronized false
        put(key, operation, ParticipantCheckIn.Processing(stage))
        true
    }

    // 応答は、その操作がいまも現在の処理であるときにだけ反映する。
    private fun settle(key: CheckInKey, operation: Long, next: ParticipantCheckIn) {
        synchronized(lock) {
            val current = entries[key]
            if (current == null || current.operation != operation || current.status !is ParticipantCheckIn.Processing) return
            entries[key] = Entry(operation, next)
            publish()
        }
    }

    private fun put(key: CheckInKey, operation: Long, status: ParticipantCheckIn) {
        entries[key] = Entry(operation, status)
        publish()
    }

    private fun publish() {
        state.value = CheckInSnapshot(entries.mapValues { it.value.status })
    }

    private suspend fun <T> observe(unconfirmed: T, call: suspend () -> T): T = try {
        withTimeoutOrNull(operationTimeout) { call() } ?: unconfirmed
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        unconfirmed
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        unconfirmed
    }

    private class Entry(val operation: Long, val status: ParticipantCheckIn)
}

fun createCheckInCore(
    source: AdmissionSource,
    port: CheckInPort,
    clock: Clock,
    scope: CoroutineScope,
    newAttemptId: () -> CheckInAttemptId,
    operationTimeout: Duration,
): CheckInCore = CheckInCore(source, port, clock, newAttemptId, scope, operationTimeout)
