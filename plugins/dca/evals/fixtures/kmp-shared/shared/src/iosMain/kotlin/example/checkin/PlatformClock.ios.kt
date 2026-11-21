package example.checkin

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual class PlatformClock {
    actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}
