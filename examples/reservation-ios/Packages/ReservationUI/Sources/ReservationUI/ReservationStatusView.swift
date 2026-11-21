public import CheckInCore
import Kernel
public import ReservationCore
public import SwiftUI

public struct ReservationStatusView: View {
    let state: StatusState
    let endpoint: ReservationViewModel
    let onCancel: (CancelTicket) -> Void
    let onCheckIn: (ReservationAdmission) -> Void
    let onLookUpCheckIn: (CheckInStatusTicket) -> Void
    let onRetryCheckIn: (CheckInRetryTicket) -> Void
    let onChooseAgain: () -> Void

    public init(
        state: StatusState,
        endpoint: ReservationViewModel,
        onCancel: @escaping (CancelTicket) -> Void,
        onCheckIn: @escaping (ReservationAdmission) -> Void,
        onLookUpCheckIn: @escaping (CheckInStatusTicket) -> Void,
        onRetryCheckIn: @escaping (CheckInRetryTicket) -> Void,
        onChooseAgain: @escaping () -> Void
    ) {
        self.state = state
        self.endpoint = endpoint
        self.onCancel = onCancel
        self.onCheckIn = onCheckIn
        self.onLookUpCheckIn = onLookUpCheckIn
        self.onRetryCheckIn = onRetryCheckIn
        self.onChooseAgain = onChooseAgain
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if state.request == nil && state.reservations.isEmpty {
                    Text("予約の操作はまだありません。開催回の一覧から予約する開催回を選んでください。")
                        .accessibilityIdentifier("status-empty")
                }
                if let request = state.request { requestCard(request) }
                ForEach(Array(state.reservations.enumerated()), id: \.offset) { _, card in reservationCard(card) }
            }
            .padding()
        }
    }

    @ViewBuilder private func requestCard(_ card: RequestCard) -> some View {
        switch card {
        case .sending(let summary):
            StatusCard(
                tag: "request-sending", label: "送信中", summary: summary,
                description: "予約を送信しています。結果が届くまで、同じ予約を重ねて送信しません。"
            )
        case .checkingStatus(let summary):
            StatusCard(
                tag: "request-checking", label: "照会中", summary: summary,
                description: "元の予約要求の結果を確認しています。新しい予約は作成しません。"
            )
        case .unknown(let summary, let check):
            StatusCard(
                tag: "request-unknown", label: "結果不明", summary: summary,
                description: "予約が成立している可能性があります。再試行はできません。元の要求を照会してください。",
                emphasis: .caution
            ) {
                ActionButton(title: "状況を照会する", identifier: "lookup-button", action: .lookingUp(check, on: endpoint))
            }
        case .notCreated(let summary, let retry, let denial, let gate):
            StatusCard(
                tag: "request-not-created", label: "未成立が確定", summary: summary,
                description: "予約は作成されていません。"
                    + (gate == .open ? "同じ内容でもう一度送信できます。" : "")
                    + (denial.map { "\n\(createDenialMessage($0))" } ?? "")
            ) {
                switch gate {
                case .open:
                    ActionButton(title: "再試行する", identifier: "retry-button", action: .retrying(retry, on: endpoint))
                case .closed:
                    Text(retryUnavailableMessage()).font(.footnote).accessibilityIdentifier("retry-unavailable")
                }
            }
        case .soldOut(let summary):
            StatusCard(
                tag: "request-sold-out", label: "満席", summary: summary,
                description: "定員に空きがなく、予約は成立していません。開催回や参加者を選び直してください。"
            ) { chooseAgain("開催回を選び直す", id: "choose-again-button") }
        case .registrationClosed(let summary):
            StatusCard(
                tag: "request-registration-closed", label: "受付終了", summary: summary,
                description: "開始時刻を迎えたため、新規予約の受付は終了しています。予約要求は送信していません。別の開催回を選んでください。"
            ) { chooseAgain("別の開催回を選ぶ", id: "choose-another-button") }
        case .eligibilityLost(let summary, let reason):
            StatusCard(
                tag: "request-eligibility-lost", label: "資格の再確認が必要です", summary: summary,
                description: "\(eligibilityLostMessage(reason))予約要求は送信していません。開催回を選んで、資格を確認し直してください。",
                emphasis: .caution
            ) { chooseAgain("開催回の一覧に戻る", id: "choose-again-button") }
        }
    }

    @ViewBuilder private func reservationCard(_ card: ReservationCard) -> some View {
        switch card {
        case .reserved(let reserved):
            StatusCard(
                tag: "reservation-\(reserved.reservationId)", label: "予約確定", summary: reserved.summary,
                description: "予約番号 \(reserved.reservationId)"
                    + (reserved.lastCancelFailure.map { "\n\(cancelFailureMessage($0))" } ?? ""),
                emphasis: .positive
            ) {
                ForEach(Array(reserved.participants.enumerated()), id: \.offset) { _, row in
                    participant(reserved.reservationId, row)
                }
                switch reserved.cancellation {
                case .open:
                    Button("予約を取り消す", role: .destructive) { onCancel(reserved.cancel) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("cancel-button-\(reserved.reservationId)")
                case .closed:
                    Text(cancelUnavailableMessage()).font(.footnote)
                        .accessibilityIdentifier("cancel-unavailable-\(reserved.reservationId)")
                }
            }
        case .cancelling(let summary, let id):
            StatusCard(
                tag: "reservation-\(id)", label: "取消中", summary: summary,
                description: "予約番号 \(id) の取消結果を待っています。結果が届くまで、この予約への操作はできません。"
            )
        case .cancelled(let summary, let id):
            StatusCard(
                tag: "reservation-\(id)", label: "取消済み", summary: summary,
                description: "予約番号 \(id) は取り消されました。履歴として表示しています。"
            )
        }
    }

    @ViewBuilder private func participant(_ reservationId: String, _ row: ParticipantCheckInRow) -> some View {
        let tag = "\(reservationId)-\(row.admission.participant.value)"
        VStack(alignment: .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name).font(.body)
                Text(checkInStateLabel(row.view)).font(.subheadline)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("checkin-state-\(tag)")
            checkInAction(tag, row)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func checkInAction(_ tag: String, _ row: ParticipantCheckInRow) -> some View {
        switch row.view {
        case .notCheckedIn, .notAdmitted:
            windowed(tag, row.window) {
                Button("\(row.name)を受付する") { onCheckIn(row.admission) }
                    .buttonStyle(.bordered).accessibilityIdentifier("checkin-button-\(tag)")
            }
        case .unknown(let ticket):
            Button("\(row.name)の受付状況を照会する") { onLookUpCheckIn(ticket) }
                .buttonStyle(.bordered).accessibilityIdentifier("checkin-lookup-button-\(tag)")
        case .notCreated(let retry):
            windowed(tag, row.window) {
                Button("\(row.name)をもう一度受付する") { onRetryCheckIn(retry) }
                    .buttonStyle(.bordered).accessibilityIdentifier("checkin-retry-button-\(tag)")
            }
        case .processing, .checkedIn:
            EmptyView()
        }
    }

    @ViewBuilder private func windowed<Action: View>(_ tag: String, _ window: CheckInWindow, @ViewBuilder action: () -> Action)
        -> some View
    {
        if let message = checkInWindowMessage(window) {
            Text(message).font(.footnote).accessibilityIdentifier("checkin-window-\(tag)")
        } else {
            action()
        }
    }

    private func chooseAgain(_ title: String, id: String) -> some View {
        Button(title, action: onChooseAgain).buttonStyle(.bordered).accessibilityIdentifier(id)
    }
}

enum Emphasis { case neutral, positive, caution }

struct StatusCard<Actions: View>: View {
    let tag: String
    let label: String
    let summary: BookingSummary
    let description: String
    let emphasis: Emphasis
    let actions: Actions

    init(
        tag: String, label: String, summary: BookingSummary, description: String, emphasis: Emphasis = .neutral,
        @ViewBuilder actions: () -> Actions
    ) {
        self.tag = tag
        self.label = label
        self.summary = summary
        self.description = description
        self.emphasis = emphasis
        self.actions = actions()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.headline)
            Text(summary.workshopName)
            Text("\(formatStart(summary.start)) 開始 ・ \(summary.participantNames.joined(separator: "、"))")
                .font(.footnote)
            Text(description).font(.subheadline)
            actions
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(background, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(tag)
    }

    private var background: Color {
        switch emphasis {
        case .neutral: return Color.secondary.opacity(0.12)
        case .positive: return Color.green.opacity(0.18)
        case .caution: return Color.orange.opacity(0.2)
        }
    }
}

extension StatusCard where Actions == EmptyView {
    init(tag: String, label: String, summary: BookingSummary, description: String, emphasis: Emphasis = .neutral) {
        self.init(tag: tag, label: label, summary: summary, description: description, emphasis: emphasis) { EmptyView() }
    }
}
