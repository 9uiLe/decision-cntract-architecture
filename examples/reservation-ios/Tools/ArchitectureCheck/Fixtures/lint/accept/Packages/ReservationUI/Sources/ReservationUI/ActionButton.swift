import SwiftUI

struct ActionButton: View {
    let title: String
    let action: RequestAction

    var body: some View {
        Button(title) { action.execute() }
    }
}
