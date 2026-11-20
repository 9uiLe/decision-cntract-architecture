package example.adapter.simulator

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

data class SimulatorObservations(
    val createRequests: Int = 0,
    val lookupRequests: Int = 0,
    val cancelRequests: Int = 0,
    val activeReservations: Int = 0,
    val heldCalls: Int = 0,
    val queuedCreate: Int = 0,
    val queuedLookup: Int = 0,
    val queuedCancel: Int = 0,
)

// Port とは別の面として公開する。検証用の入口には制御と観測だけを渡し、予約の作成・照会・取消を直接呼べないようにする。
interface SimulatorControls {
    val observations: StateFlow<SimulatorObservations>
    fun enqueueCreate(behavior: CreateBehavior)
    fun enqueueLookup(behavior: LookupBehavior)
    fun enqueueCancel(behavior: CancelBehavior)
    fun releaseHeld()
    fun reset()
}
