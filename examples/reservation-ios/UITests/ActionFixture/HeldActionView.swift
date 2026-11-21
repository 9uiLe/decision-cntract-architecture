import ReservationUI
import SwiftUI

// 負例。製品の画面と同じ文言・識別子で、最初に表示した操作を @State に保持する。
struct HeldActionView: View {
    let status: StatusState
    let endpoint: ReservationViewModel
    @State private var held: RequestAction?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if let current = content {
                    Text(current.heading).font(.headline).accessibilityIdentifier(current.card)
                    Text(String(repeating: "開催回と参加者、予約の状況を表示する欄。\n", count: 6)).font(.footnote)
                    Button(current.label) { (held ?? current.action).execute() }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier(current.button)
                        .onAppear { if held == nil { held = current.action } }
                }
            }
        }
    }

    private struct Content {
        let heading: String
        let label: String
        let card: String
        let button: String
        let action: RequestAction
    }

    private var content: Content? {
        guard let request = status.request else { return nil }
        switch request {
        case .unknown(_, let check):
            return Content(
                heading: "結果不明", label: "状況を照会する", card: "request-unknown", button: "lookup-button",
                action: .lookingUp(check, on: endpoint)
            )
        case .notCreated(_, let retry, _, _):
            return Content(
                heading: "未成立が確定", label: "再試行する", card: "request-not-created", button: "retry-button",
                action: .retrying(retry, on: endpoint)
            )
        case .sending, .checkingStatus, .soldOut, .registrationClosed, .eligibilityLost: return nil
        }
    }
}
