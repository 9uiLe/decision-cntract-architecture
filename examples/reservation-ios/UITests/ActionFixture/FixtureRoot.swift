import ReservationUI
import SimulatorAdapter
import SwiftUI

// 製品の画面（ReservationStatusView と ActionButton）をそのまま使う product と、避けたい保持の仕方を再現した負例を、同じ文言で並べる。
struct FixtureRoot: View {
    @StateObject private var model: FixtureModel
    @State private var hostCount = 0

    init(configuration: FixtureConfiguration) {
        _model = StateObject(wrappedValue: FixtureModel(configuration: configuration))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button("host=\(hostCount)") { hostCount += 1 }.accessibilityIdentifier("host-count")
                Button("T2 を表示") { model.showSecond() }.accessibilityIdentifier("show-t2")
                Button("実行先 B を表示") { model.showEndpointB() }.accessibilityIdentifier("show-endpoint-b")
            }
            component.frame(height: 140).accessibilityIdentifier("shown-area")
            ObservationPanel(model: model, endpointA: model.endpointA, endpointB: model.endpointB)
        }
        .padding()
        .task { await model.start() }
    }

    @ViewBuilder private var component: some View {
        if let shown = model.shown {
            switch model.configuration.variant {
            case .product, .removeDuplicates:
                ReservationStatusView(
                    state: shown.status, endpoint: shown.endpoint,
                    onCancel: { _ in }, onCheckIn: { _ in }, onLookUpCheckIn: { _ in }, onRetryCheckIn: { _ in }, onChooseAgain: {}
                )
            case .heldInState:
                HeldActionView(status: shown.status, endpoint: shown.endpoint)
            case .heldByTicket:
                HeldActionView(status: shown.status, endpoint: shown.endpoint)
                    .id(shown.status.request?.ticketIdentity)
            }
        }
    }
}

// 検証の観測欄。画面の境界の外側にだけ置き、券の識別子は画面に出さない。
// reservations は Simulator が持つ成立件数、core-reservations は Core が成立を観測した件数。応答喪失の後は前者だけが先に増える。
// 受理は、Core が現在の券と === で照合して受け付けた結果、外部サービスへ届いた要求（回数と要求 ID）で観測する。
struct ObservationPanel: View {
    @ObservedObject var model: FixtureModel
    @ObservedObject var endpointA: ReservationViewModel
    @ObservedObject var endpointB: ReservationViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("phase=\(model.phase)").accessibilityIdentifier("observe-phase")
            Text("offered=\(label(of: model.offered))").accessibilityIdentifier("observe-offered")
            Text("published=\(label(of: model.shown))").accessibilityIdentifier("observe-published")
            Text("lookups=\((model.observed?.lookUpRequests ?? 0))").accessibilityIdentifier("observe-lookups")
            Text("lookup-target=\(target)").accessibilityIdentifier("observe-lookup-target")
            Text("creates=\((model.observed?.createRequests ?? 0))").accessibilityIdentifier("observe-creates")
            Text("core-reservations=\(endpointA.state.status.reservations.count)").accessibilityIdentifier("observe-core-reservations")
            Text("reservations=\((model.observed?.activeReservations ?? 0))").accessibilityIdentifier("observe-reservations")
            Text("notice-a=\(endpointA.notice?.message ?? "none")").accessibilityIdentifier("observe-notice-a")
            Text("notice-b=\(endpointB.notice?.message ?? "none")").accessibilityIdentifier("observe-notice-b")
        }
        .font(.caption2)
    }

    // 外側の status が持つ券と実行先。offered は流れへ渡した値、published は画面に届いた値（描画と同じ shown から作る）。
    // カードの文言には出さない。
    private func label(of shown: Shown?) -> String {
        guard let shown else { return "none" }
        let ticket = shown.status.request?.ticketIdentity
        let ticketLabel: String
        if model.firstTicket == model.secondTicket {
            ticketLabel = "same"
        } else if ticket == model.firstTicket {
            ticketLabel = "first"
        } else if ticket == model.secondTicket {
            ticketLabel = "second"
        } else {
            ticketLabel = "other"
        }
        return "\(ticketLabel)@\(shown.endpoint === endpointA ? "A" : "B")"
    }

    private var target: String {
        guard let last = model.observed?.lastLookedUpRequest else { return "none" }
        guard let first = model.firstRequest, let second = model.secondRequest, first != second else { return "same-request" }
        if last == second { return "second" }
        if last == first { return "first" }
        return "other"
    }
}
