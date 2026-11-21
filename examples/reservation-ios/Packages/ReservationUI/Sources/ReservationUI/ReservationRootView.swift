import ReservationCore
public import SwiftUI

enum Route: Hashable {
    case details
    case status
}

public struct ReservationRootView: View {
    @ObservedObject var viewModel: ReservationViewModel
    @State private var path: [Route] = []

    public init(viewModel: ReservationViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack(path: $path) {
            SessionListView(
                state: viewModel.state.list,
                onSelect: { if viewModel.selectWorkshop($0) == .accepted { path = [.details] } },
                onOpenStatus: { path = [.status] }
            )
            .navigationTitle("ワークショップ予約")
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .details:
                    SessionDetailsView(
                        state: viewModel.state.details,
                        onToggle: { _ = viewModel.toggleParticipant($0) },
                        onRequestEligibility: { _ = viewModel.requestEligibility() },
                        onConfirm: { viewModel.confirm() },
                        onSubmit: { if showsStatus(viewModel.submit($0)) { path = [.status] } }
                    )
                    .navigationTitle("予約内容の確認")
                case .status:
                    ReservationStatusView(
                        state: viewModel.state.status,
                        endpoint: viewModel,
                        onCancel: { _ = viewModel.cancel($0) },
                        onCheckIn: { viewModel.checkInParticipant($0) },
                        onLookUpCheckIn: { viewModel.checkParticipantStatus($0) },
                        onRetryCheckIn: { viewModel.retryCheckIn($0) },
                        onChooseAgain: { path = [] }
                    )
                    .navigationTitle("予約の状況")
                }
            }
        }
        .safeAreaInset(edge: .bottom) { noticeBanner }
        .task { viewModel.start() }
    }

    private func showsStatus(_ acceptance: Acceptance) -> Bool {
        switch acceptance {
        case .accepted: return true
        case .rejected(let reason): return reason == .registrationClosed
        }
    }

    @ViewBuilder private var noticeBanner: some View {
        if let notice = viewModel.notice {
            HStack(alignment: .top, spacing: 12) {
                Text(notice.message).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
                Button("閉じる") { viewModel.consumeNotice(notice) }.accessibilityIdentifier("notice-dismiss")
            }
            .padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("notice")
        }
    }
}
