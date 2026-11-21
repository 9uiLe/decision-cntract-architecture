package example.checkin

expect class PlatformClock() {
    fun nowMillis(): Long
}

class CheckInService(private val clock: PlatformClock) {
    fun checkIn(startMillis: Long): Boolean = clock.nowMillis() < startMillis
}
