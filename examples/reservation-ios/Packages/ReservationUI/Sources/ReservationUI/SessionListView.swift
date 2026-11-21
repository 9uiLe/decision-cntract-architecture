public import Kernel
public import SwiftUI

public struct SessionListView: View {
    let state: ListState
    let onSelect: (SessionId) -> Void
    let onOpenStatus: () -> Void

    public init(state: ListState, onSelect: @escaping (SessionId) -> Void, onOpenStatus: @escaping () -> Void) {
        self.state = state
        self.onSelect = onSelect
        self.onOpenStatus = onOpenStatus
    }

    public var body: some View {
        List {
            if let headline = ongoingHeadline {
                Section {
                    Button(action: onOpenStatus) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(headline).font(.headline)
                            Text("予約の状況を開く").font(.subheadline).foregroundStyle(.tint)
                        }
                    }
                    .accessibilityIdentifier("ongoing-banner")
                }
            }
            Section("開催回を選んでください") {
                ForEach(state.workshops, id: \.id.value) { workshop in
                    Button { onSelect(workshop.id) } label: { row(workshop) }
                        .accessibilityIdentifier("session-\(workshop.id.value)")
                }
            }
        }
    }

    private var ongoingHeadline: String? {
        if let request = state.ongoing.request { return requestHeadline(request) }
        if !state.ongoing.reservations.isEmpty { return "予約 \(state.ongoing.reservations.count) 件の状況を確認できます" }
        return nil
    }

    private func row(_ workshop: WorkshopItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(workshop.name).font(.headline)
            Text("\(formatStart(workshop.start)) 開始").font(.subheadline).foregroundStyle(.secondary)
            Text("残席の目安 \(workshop.seatsHint)（参考。予約の成立は送信後の結果で決まります）")
                .font(.footnote).foregroundStyle(.secondary)
            if case .required(let name) = workshop.requirement {
                Text("資格が必要: \(name)").font(.footnote).foregroundStyle(.tint)
                    .accessibilityIdentifier("requires-\(workshop.id.value)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
