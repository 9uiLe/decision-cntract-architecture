import SwiftUI
import DcaShared

private struct ComposeScreen: UIViewControllerRepresentable {
    let make: () -> UIViewController

    func makeUIViewController(context: Context) -> UIViewController { make() }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// 検証用の画面は起動引数で明示したときだけ表示する。予約の状態はプロセスの寿命の間だけ保持するため、
// 同じプロセスで予約画面と切り替えられるようにする。
private let verificationLaunchArgument = "-dca-verification"

struct RootView: View {
    private let showsVerification = ProcessInfo.processInfo.arguments.contains(verificationLaunchArgument)

    var body: some View {
        if showsVerification {
            TabView {
                ComposeScreen(make: IosEntriesKt.reservationViewController)
                    .tabItem { Text("予約") }
                ComposeScreen(make: IosEntriesKt.debugControlViewController)
                    .tabItem { Text("検証用の操作") }
            }
        } else {
            ComposeScreen(make: IosEntriesKt.reservationViewController)
        }
    }
}
