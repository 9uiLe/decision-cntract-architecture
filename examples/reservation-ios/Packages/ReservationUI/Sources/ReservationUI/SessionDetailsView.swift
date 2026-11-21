public import Kernel
public import ReservationCore
public import SwiftUI

public struct SessionDetailsView: View {
    let state: DetailsState
    let onToggle: (ParticipantId) -> Void
    let onRequestEligibility: () -> Void
    let onConfirm: () -> Void
    let onSubmit: (ConfirmedDraft) -> Void

    public init(
        state: DetailsState,
        onToggle: @escaping (ParticipantId) -> Void,
        onRequestEligibility: @escaping () -> Void,
        onConfirm: @escaping () -> Void,
        onSubmit: @escaping (ConfirmedDraft) -> Void
    ) {
        self.state = state
        self.onToggle = onToggle
        self.onRequestEligibility = onRequestEligibility
        self.onConfirm = onConfirm
        self.onSubmit = onSubmit
    }

    public var body: some View {
        if let workshop = state.workshop {
            Form {
                header(workshop)
                participants(workshop)
                stage
            }
        } else {
            Text("開催回が選択されていません。一覧から選んでください。").padding()
        }
    }

    private func header(_ workshop: WorkshopItem) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(workshop.name).font(.title3.bold())
                Text("\(formatStart(workshop.start)) 開始").foregroundStyle(.secondary)
                Text("取消は開始の24時間前より前まで受け付けます。24時間前ちょうどは取り消せません。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text(requirementText(workshop.requirement)).font(.footnote).foregroundStyle(.secondary)
                    .accessibilityIdentifier("requirement")
            }
        }
    }

    private func requirementText(_ requirement: RequirementView) -> String {
        switch requirement {
        case .notRequired: return "資格は必要ありません。"
        case .required(let name): return "参加には「\(name)」の資格が必要です。参加者全員の確認が必要です。"
        }
    }

    private func participants(_ workshop: WorkshopItem) -> some View {
        Section {
            ForEach(state.participants, id: \.id.value) { row in
                Button { onToggle(row.id) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: row.selected ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(row.selected ? Color.accentColor : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name)
                            if let badge = row.qualification {
                                Text(qualificationLabel(badge)).font(.footnote).foregroundStyle(.secondary)
                                    .accessibilityIdentifier("qualification-\(row.id.value)")
                            }
                        }
                    }
                }
                .foregroundStyle(.primary)
                .accessibilityIdentifier("participant-\(row.id.value)")
                .accessibilityValue(row.selected ? "選択中" : "未選択")
            }
            if case .required = workshop.requirement {
                Button("資格を確認する", action: onRequestEligibility)
                    .disabled(!state.canRequestEligibility)
                    .accessibilityIdentifier("eligibility-button")
            }
        } header: {
            Text("参加者（\(state.participants.filter(\.selected).count)名を選択中）")
                .accessibilityIdentifier("participant-count")
        }
    }

    @ViewBuilder private var stage: some View {
        switch state.stage {
        case .editing:
            Section {
                Text("編集中").font(.subheadline.bold()).foregroundStyle(.tint).accessibilityIdentifier("stage-editing")
                Button("内容を確認する", action: onConfirm)
                    .disabled(!state.canConfirm)
                    .accessibilityIdentifier("confirm-button")
                Text(state.confirmHint ?? "確認しただけでは予約は送信されず、席も確保されません。")
                    .font(.footnote).foregroundStyle(.secondary)
                    .accessibilityIdentifier("confirm-note")
            }
        case .confirmed(let draft, let gate):
            Section {
                Text("確認済み").font(.subheadline.bold()).foregroundStyle(.tint).accessibilityIdentifier("stage-confirmed")
                switch gate {
                case .open:
                    Button("この内容で予約する") { onSubmit(draft) }.accessibilityIdentifier("submit-button")
                case .closed:
                    Text(submitUnavailableMessage()).font(.footnote).foregroundStyle(.secondary)
                        .accessibilityIdentifier("submit-unavailable")
                }
            }
        }
    }
}
