import SwiftUI

// 受け取った操作をそのまま実行する。別の値へ写して保持すると、券か実行先が変わった更新の後も古い操作が残る。
struct ActionButton: View {
    let title: String
    let identifier: String
    let action: RequestAction

    var body: some View {
        Button(title) { action.execute() }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier(identifier)
    }
}
