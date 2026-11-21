public import Kernel
import Foundation

private let weekdays = ["日", "月", "火", "水", "木", "金", "土"]

public func formatStart(_ time: EpochMillis) -> String {
    let parts = tokyoParts(time)
    return "\(date(parts)) \(hourMinute(parts))"
}

public func formatPrecise(_ time: EpochMillis) -> String {
    let parts = tokyoParts(time)
    return "\(date(parts)) \(hourMinute(parts)):\(twoDigits(parts.second ?? 0))"
}

private func tokyoParts(_ time: EpochMillis) -> DateComponents {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Asia/Tokyo") ?? .gmt
    return calendar.dateComponents(
        [.month, .day, .weekday, .hour, .minute, .second],
        from: Date(timeIntervalSince1970: Double(time.value) / 1000)
    )
}

private func date(_ parts: DateComponents) -> String {
    "\(parts.month ?? 0)月\(parts.day ?? 0)日(\(weekdays[((parts.weekday ?? 1) - 1) % 7]))"
}

private func hourMinute(_ parts: DateComponents) -> String {
    "\(twoDigits(parts.hour ?? 0)):\(twoDigits(parts.minute ?? 0))"
}

private func twoDigits(_ value: Int) -> String { value < 10 ? "0\(value)" : "\(value)" }
