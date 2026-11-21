import SwiftUI

struct ActionButton: View {
    let title: String
    let action: RequestAction
    @State private var held: RequestAction?  // EXPECT: action_button_no_cache

    var body: some View {
        Button(title) { (held ?? action).execute() }
            .onAppear { if held == nil { held = action } }  // EXPECT: action_button_no_cache
            .id(title)  // EXPECT: action_button_no_cache
            .equatable()  // EXPECT: action_button_no_cache
    }
}
