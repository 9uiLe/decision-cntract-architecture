package example.adapter.simulator

import example.eligibility.core.QualificationKind
import example.kernel.EpochMillis
import example.kernel.ParticipantId
import example.reservation.core.ReservationId
import kotlinx.coroutines.flow.StateFlow

enum class Delivery { Respond, Lose, Hold }

enum class CreateEffect { Create, SoldOut, Reject }

data class CreateBehavior(val effect: CreateEffect, val delivery: Delivery) {
    companion object {
        val Succeed = CreateBehavior(CreateEffect.Create, Delivery.Respond)
        val SoldOut = CreateBehavior(CreateEffect.SoldOut, Delivery.Respond)
        val RejectBeforeCreate = CreateBehavior(CreateEffect.Reject, Delivery.Respond)
        val LoseResponseAfterCreate = CreateBehavior(CreateEffect.Create, Delivery.Lose)
        val HoldResponse = CreateBehavior(CreateEffect.Create, Delivery.Hold)
    }
}

enum class LookupBehavior { Normal, NotFound, Fail, Hold }

enum class CancelBehavior { Normal, Decline, Lose, Hold }

enum class UsageBehavior { Normal, Fail, Hold }

enum class QualificationBehavior { Normal, NoResponse, Unrecognized, Hold }

enum class CheckInEffect { Record, Reject }

data class CheckInBehavior(val effect: CheckInEffect, val delivery: Delivery) {
    companion object {
        val Succeed = CheckInBehavior(CheckInEffect.Record, Delivery.Respond)
        val RejectBeforeRecord = CheckInBehavior(CheckInEffect.Reject, Delivery.Respond)
        val LoseResponseAfterRecord = CheckInBehavior(CheckInEffect.Record, Delivery.Lose)
        val HoldResponse = CheckInBehavior(CheckInEffect.Record, Delivery.Hold)
    }
}

data class QualificationHolding(val participant: ParticipantId, val kind: QualificationKind, val expiresAt: EpochMillis)

data class CheckInRecordCount(val reservation: ReservationId, val participant: ParticipantId, val records: Int)

// 呼出回数と件数は別に数える。結果の違い（成立・重複・拒否）を回数から逆算せずに確かめられるようにする。
data class SimulatorObservations(
    val createRequests: Int = 0,
    val lookupRequests: Int = 0,
    val cancelRequests: Int = 0,
    val usageRequests: Int = 0,
    val qualificationRequests: Int = 0,
    val checkInCreateRequests: Int = 0,
    val checkInLookupRequests: Int = 0,
    val checkInRepeatedForRecordedTarget: Int = 0,
    val activeReservations: Int = 0,
    val checkInRecords: List<CheckInRecordCount> = emptyList(),
    val heldCalls: Int = 0,
    val queuedCreate: Int = 0,
    val queuedLookup: Int = 0,
    val queuedCancel: Int = 0,
    val queuedUsage: Int = 0,
    val queuedQualification: Int = 0,
    val queuedCheckInCreate: Int = 0,
    val queuedCheckInLookup: Int = 0,
)

// Port とは別の面として公開する。検証用の入口には制御と観測だけを渡し、Port の呼び出しを直接させない。
interface SimulatorControls {
    val observations: StateFlow<SimulatorObservations>
    fun enqueueCreate(behavior: CreateBehavior)
    fun enqueueLookup(behavior: LookupBehavior)
    fun enqueueCancel(behavior: CancelBehavior)
    fun enqueueUsage(behavior: UsageBehavior)
    fun enqueueQualification(behavior: QualificationBehavior)
    fun enqueueCheckInCreate(behavior: CheckInBehavior)
    fun enqueueCheckInLookup(behavior: LookupBehavior)
    fun grantQualification(participant: ParticipantId, kind: QualificationKind, expiresAt: EpochMillis)
    fun revokeQualification(participant: ParticipantId, kind: QualificationKind)
    fun releaseHeld()
    fun reset()
}
