import EligibilityCore
import Kernel
import Testing

@testable import SimulatorAdapter

private let pottery = QualificationKind(id: "pottery-advanced")
private let roasting = QualificationKind(id: "roasting-advanced")

@Suite
struct QualificationWireTests {
    @Test func 固定した入力を型付きの外部データに解析し期限の事実として観測に変換する() {
        let text = "held=true&code=POTTERY-ADV&valid_until=2026-12-31T00:00:00Z"
        #expect(QualificationWireFormat.parse(text) == QualificationWire(held: true, code: "POTTERY-ADV", validUntil: "2026-12-31T00:00:00Z"))
        #expect(QualificationDecoder.decode(text, requested: pottery) == .held(expiresAt: EpochMillis(1_798_675_200_000)))
        #expect(QualificationDecoder.decode("held=false", requested: pottery) == .notHeld)
    }

    @Test(arguments: [
        "held=true&code=UNKNOWN&valid_until=2026-12-31T00:00:00Z",
        "held=true&code=ROASTING-ADV&valid_until=2026-12-31T00:00:00Z",
        "held=true&code=POTTERY-ADV",
        "held=true&code=POTTERY-ADV&valid_until=tomorrow",
        "held=true&valid_until=2026-12-31T00:00:00Z",
        "unrecognized-response",
        "held=maybe",
    ])
    func 未知のコードや別の種別のコードや欠けた期限や形式の崩れは有効な資格と推測しない(text: String) {
        #expect(QualificationDecoder.decode(text, requested: pottery) == .unavailable)
    }

    @Test func 書き出した外部データは同じ内容に解析される() {
        let wire = heldWire(kind: roasting, expiresAt: EpochMillis(1_798_675_200_123))
        #expect(QualificationWireFormat.parse(QualificationWireFormat.render(wire)) == wire)
        #expect(QualificationDecoder.decode(QualificationWireFormat.render(wire), requested: roasting) == .held(expiresAt: EpochMillis(1_798_675_200_123)))
    }

    @Test func 同じキーが重複した外部データは後の値で上書きせず解釈しない() {
        #expect(QualificationWireFormat.parse("held=false&held=true&code=POTTERY-ADV&valid_until=2026-12-31T00:00:00Z") == nil)
    }

    @Test func 未知のキーは無視し重複していない入力の解釈は変えない() {
        let text = "held=true&code=POTTERY-ADV&valid_until=2026-12-31T00:00:00Z&extra=1"
        #expect(QualificationDecoder.decode(text, requested: pottery) == .held(expiresAt: EpochMillis(1_798_675_200_000)))
    }
}
