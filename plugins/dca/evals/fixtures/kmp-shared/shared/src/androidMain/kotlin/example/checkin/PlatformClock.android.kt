package example.checkin

actual class PlatformClock {
    actual fun nowMillis(): Long = System.currentTimeMillis()
}
