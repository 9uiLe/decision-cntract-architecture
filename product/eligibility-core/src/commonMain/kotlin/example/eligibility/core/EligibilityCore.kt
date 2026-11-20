package example.eligibility.core

import example.kernel.Clock
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

// 資格の有無・期限・確認の進行は、(参加者, 資格の種別) ごとにここだけが持つ。
// 期限の判定は status と verify の二か所に限り、利用する Core には複製させない。
class EligibilityCore internal constructor(
    private val port: EligibilityPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val operationTimeout: Duration,
) : EligibilityAuthority {
    private val lock = SynchronizedObject()
    private var lastOperation = 0L
    private val entries = LinkedHashMap<EligibilityKey, Entry>()
    private val state = MutableStateFlow(EligibilitySnapshot(emptyMap()))

    override val snapshot: StateFlow<EligibilitySnapshot> = state.asStateFlow()

    override fun status(participant: ParticipantId, kind: QualificationKind): EligibilityStatus =
        synchronized(lock) { entries[EligibilityKey(participant, kind)].toStatus() }

    override fun request(participant: ParticipantId, kind: QualificationKind): EligibilityRequestAcceptance {
        val key = EligibilityKey(participant, kind)
        val operation = synchronized(lock) {
            if (entries[key] is Entry.Checking) return EligibilityRequestAcceptance.Rejected(EligibilityRequestRejection.Busy)
            val operation = ++lastOperation
            entries[key] = Entry.Checking(operation)
            publish()
            operation
        }
        scope.launch {
            val observation = observe(QualificationObservation.Unavailable) { port.fetch(participant, kind) }
            settle(key, operation, observation)
        }
        return EligibilityRequestAcceptance.Accepted
    }

    override suspend fun verify(certificates: List<EligibilityCertificate>): Reverification {
        val keys = certificates.map { EligibilityKey(it.participant, it.kind) }
        if (keys.toSet().size != keys.size) return Reverification.Failed(ReverificationFailure.TargetMismatch)
        val previous = synchronized(lock) { keys.map(entries::get) }
        val observations = keys.map { key -> observe(QualificationObservation.Unavailable) { port.fetch(key.participant, key.kind) } }
        val decidedAt = clock.now()
        synchronized(lock) {
            var updated = false
            keys.forEachIndexed { index, key ->
                val before = previous[index]
                if (entries[key] === before && before !is Entry.Checking) {
                    entries[key] = Entry.Settled(outcomeOf(key, observations[index], decidedAt))
                    updated = true
                }
            }
            if (updated) publish()
        }
        val refreshed = mutableListOf<EligibilityCertificate>()
        for ((index, certificate) in certificates.withIndex()) {
            if (decidedAt >= certificate.expiresAt) return Reverification.Failed(ReverificationFailure.Expired)
            when (val observation = observations[index]) {
                is QualificationObservation.Held -> {
                    if (decidedAt >= observation.expiresAt) return Reverification.Failed(ReverificationFailure.Expired)
                    refreshed += EligibilityCertificate(certificate.participant, certificate.kind, decidedAt, observation.expiresAt)
                }
                QualificationObservation.NotHeld -> return Reverification.Failed(ReverificationFailure.NotHeld)
                QualificationObservation.Unavailable -> return Reverification.Failed(ReverificationFailure.Unconfirmed)
            }
        }
        return Reverification.Verified(refreshed)
    }

    private fun settle(key: EligibilityKey, operation: Long, observation: QualificationObservation) {
        synchronized(lock) {
            val current = entries[key]
            if (current !is Entry.Checking || current.operation != operation) return
            entries[key] = Entry.Settled(outcomeOf(key, observation))
            publish()
        }
    }

    private fun outcomeOf(
        key: EligibilityKey,
        observation: QualificationObservation,
        checkedAt: EpochMillis = clock.now(),
    ): EligibilityOutcome =
        when (observation) {
            is QualificationObservation.Held ->
                if (checkedAt < observation.expiresAt) {
                    EligibilityOutcome.Eligible(EligibilityCertificate(key.participant, key.kind, checkedAt, observation.expiresAt))
                } else {
                    EligibilityOutcome.Expired
                }
            QualificationObservation.NotHeld -> EligibilityOutcome.NotHeld
            QualificationObservation.Unavailable -> EligibilityOutcome.Unknown
        }

    // 資格の有効性は now < expiresAt。期限ちょうどから無効。
    private fun isCurrent(expiresAt: EpochMillis): Boolean = clock.now() < expiresAt

    private fun Entry?.toStatus(): EligibilityStatus = when (this) {
        null -> EligibilityStatus.NotChecked
        is Entry.Checking -> EligibilityStatus.Checking
        is Entry.Settled -> EligibilityStatus.Settled(
            if (outcome is EligibilityOutcome.Eligible && !isCurrent(outcome.certificate.expiresAt)) {
                EligibilityOutcome.Expired
            } else {
                outcome
            },
        )
    }

    private fun publish() {
        state.value = EligibilitySnapshot(entries.mapValues { it.value.toStatus() })
    }

    // 時間切れ・例外・Port 側のキャンセルは、確認できなかったこととして扱う。外側の scope が止められた場合だけ伝える。
    private suspend fun <T> observe(unconfirmed: T, call: suspend () -> T): T = try {
        withTimeoutOrNull(operationTimeout) { call() } ?: unconfirmed
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        unconfirmed
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        unconfirmed
    }

    private sealed interface Entry {
        class Checking(val operation: Long) : Entry
        class Settled(val outcome: EligibilityOutcome) : Entry
    }
}

fun createEligibilityCore(
    port: EligibilityPort,
    clock: Clock,
    scope: CoroutineScope,
    operationTimeout: Duration,
): EligibilityCore = EligibilityCore(port, clock, scope, operationTimeout)
