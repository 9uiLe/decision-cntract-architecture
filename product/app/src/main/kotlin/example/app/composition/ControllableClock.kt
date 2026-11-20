package example.app.composition

import example.reservation.core.Clock
import example.reservation.core.EpochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ControllableClock(private val initial: EpochMillis) : Clock {
    private val current = MutableStateFlow(initial)
    val time: StateFlow<EpochMillis> get() = current

    override fun now(): EpochMillis = current.value

    fun set(time: EpochMillis) {
        current.value = time
    }

    fun advance(millis: Long) {
        current.value = EpochMillis(current.value.value + millis)
    }

    fun reset() {
        current.value = initial
    }
}
