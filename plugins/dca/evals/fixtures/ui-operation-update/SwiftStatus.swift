import Combine
import SwiftUI

final class StatusCheckTicket: Sendable {}

struct StatusInput {
    let message: String
    let ticket: StatusCheckTicket
    let check: (StatusCheckTicket) -> Void
}

func screenInputs(_ updates: AnyPublisher<StatusInput, Never>) -> AnyPublisher<StatusInput, Never> {
    updates.removeDuplicates { $0.message == $1.message }.eraseToAnyPublisher()
}

struct StatusCard: View {
    let input: StatusInput

    var body: some View {
        VStack {
            Text(input.message)
            Button("状況を照会する") { input.check(input.ticket) }
        }
    }
}
