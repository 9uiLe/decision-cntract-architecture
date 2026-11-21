import SwiftUI

@main
struct ActionFixtureApp: App {
    var body: some Scene {
        WindowGroup { FixtureRoot(configuration: FixtureConfiguration(arguments: ProcessInfo.processInfo.arguments)) }
    }
}
