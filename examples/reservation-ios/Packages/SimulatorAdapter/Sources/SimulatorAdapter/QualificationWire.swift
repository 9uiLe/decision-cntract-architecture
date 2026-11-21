import EligibilityCore
import Foundation
import Kernel

// 資格サービスの外部データの形式。この形式を読み書きする処理はこのファイルに閉じる。
struct QualificationWire: Equatable {
    let held: Bool
    let code: String?
    let validUntil: String?
}

enum ServiceCodes {
    private static let codes = [
        "pottery-advanced": "POTTERY-ADV",
        "roasting-advanced": "ROASTING-ADV",
    ]

    static func code(for kind: QualificationKind) -> String? { codes[kind.id] }
}

enum QualificationWireFormat {
    static func render(_ wire: QualificationWire) -> String {
        var fields = ["held=\(wire.held)"]
        if let code = wire.code { fields.append("code=\(code)") }
        if let validUntil = wire.validUntil { fields.append("valid_until=\(validUntil)") }
        return fields.joined(separator: "&")
    }

    static func parse(_ text: String) -> QualificationWire? {
        var fields: [String: String] = [:]
        for pair in text.split(separator: "&", omittingEmptySubsequences: false) {
            guard let separator = pair.firstIndex(of: "=") else { return nil }
            let key = String(pair[pair.startIndex..<separator])
            if fields[key] != nil { return nil }
            fields[key] = String(pair[pair.index(after: separator)...])
        }
        let held: Bool
        switch fields["held"] {
        case "true": held = true
        case "false": held = false
        default: return nil
        }
        return QualificationWire(held: held, code: fields["code"], validUntil: fields["valid_until"])
    }
}

extension QualificationWire {
    func toObservation(requested: QualificationKind) -> QualificationObservation {
        if !held { return .notHeld }
        guard let code, code == ServiceCodes.code(for: requested) else { return .unavailable }
        guard let validUntil, let expiresAt = Timestamp.parse(validUntil) else { return .unavailable }
        return .held(expiresAt: expiresAt)
    }
}

enum QualificationDecoder {
    static func decode(_ text: String, requested: QualificationKind) -> QualificationObservation {
        QualificationWireFormat.parse(text)?.toObservation(requested: requested) ?? .unavailable
    }
}

func heldWire(kind: QualificationKind, expiresAt: EpochMillis) -> QualificationWire {
    QualificationWire(held: true, code: ServiceCodes.code(for: kind), validUntil: Timestamp.render(expiresAt))
}

enum Timestamp {
    static func parse(_ text: String) -> EpochMillis? {
        for options in [ISO8601DateFormatter.Options.withInternetDateTime, [.withInternetDateTime, .withFractionalSeconds]] {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = options
            if let date = formatter.date(from: text) {
                return EpochMillis(Int64((date.timeIntervalSince1970 * 1000).rounded()))
            }
        }
        return nil
    }

    static func render(_ time: EpochMillis) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = time.value % 1000 == 0
            ? [.withInternetDateTime]
            : [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(time.value) / 1000))
    }
}
