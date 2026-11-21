import Combine
import ReservationUI
public import SwiftUI

// 予約画面の寿命はここで持ち、Core の寿命は AppContainer が持つ。画面を作り直しても、進行中の要求は Core に残る。
public struct ReservationEntryView: View {
    @StateObject private var viewModel: ReservationViewModel

    public init(container: AppContainer) {
        _viewModel = StateObject(wrappedValue: ReservationViewModel(
            core: container.core,
            eligibility: container.eligibility,
            checkIn: container.checkIn,
            catalog: container.catalog,
            timeChanges: container.clock.changes()
        ))
    }

    public var body: some View { ReservationRootView(viewModel: viewModel) }
}
