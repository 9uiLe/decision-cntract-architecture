import Composition
import SwiftUI

@main
struct ReservationIosApp: App {
    private let container = AppContainer()

    var body: some Scene {
        WindowGroup { RootView(container: container) }
    }
}

private let verificationLaunchArgument = "-dca-verification"

private struct RootView: View {
    let container: AppContainer
    private let showsVerification = ProcessInfo.processInfo.arguments.contains(verificationLaunchArgument)

    var body: some View {
        if showsVerification {
            TabView {
                ReservationEntryView(container: container).tabItem { Text("予約") }
                DebugControlView(container: container).tabItem { Text("検証用の操作") }
            }
        } else {
            ReservationEntryView(container: container)
        }
    }
}
