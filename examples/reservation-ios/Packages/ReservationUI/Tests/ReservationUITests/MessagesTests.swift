import CheckInCore
import EligibilityCore
import Kernel
import ReservationCore
import Testing

@testable import ReservationUI

private let implementationTerms = ["nil", "null", "Optional", "Observation", "Ticket", "Core", "Port", "Rejection", "enum", "case"]

private let rejections: [Rejection] = [
    .unknownWorkshop, .unknownParticipant, .stale, .busy, .registrationClosed, .cancellationClosed,
    .qualificationNotRequired, .noParticipants,
]
private let checkInRejections: [CheckInRejection] = [.busy, .alreadyCheckedIn, .unresolved, .stale, .notYetOpen, .closed]
private let badges: [QualificationBadge] = [.notChecked, .checking, .valid, .notHeld, .expired, .unknown]
private let failures: [ReverificationFailure] = [.notHeld, .expired, .unconfirmed, .targetMismatch]
private let stages: [ProcessingStage] = [.confirmingReservation, .recording, .lookingUp]
private let reasons: [AdmissionUnavailableReason] = [.notIncluded, .notReserved, .cancelled]
private let denials: [Denial] =
    [.reservationUnconfirmed, .notYetOpen, .closed, .wrongTarget] + reasons.map { .reservationUnavailable($0) }

private func texts() -> [String] {
    rejections.map(rejectionMessage)
        + checkInRejections.map(checkInRejectionMessage)
        + badges.map(qualificationLabel)
        + failures.map(eligibilityLostMessage)
        + failures.map { createDenialMessage(.eligibilityLost($0)) }
        + [createDenialMessage(.registrationClosed)]
        + stages.map(processingLabel)
        + denials.map(denialMessage)
        + denials.map { checkInStateLabel(.notAdmitted($0)) }
        + [CancelFailure.declined, .noResponse].map(cancelFailureMessage)
        + [checkInStateLabel(.notCheckedIn), checkInStateLabel(.checkedIn)]
        + [submitUnavailableMessage(), retryUnavailableMessage(), cancelUnavailableMessage()]
        + [CheckInWindow.notYetOpen, .closed].compactMap(checkInWindowMessage)
}

@Suite
struct MessagesTests {
    @Test func すべての状態に利用者向けの文言があり実装上の語を含まない() {
        for text in texts() {
            #expect(!text.isEmpty)
            for term in implementationTerms { #expect(!text.contains(term), "\(text) に \(term) が含まれている") }
        }
    }

    @Test func 受付できない状態は理由と次にできることを文言で示す() {
        #expect(checkInWindowMessage(.open) == nil)
        #expect(checkInWindowMessage(.notYetOpen)?.contains("30分前") == true)
        #expect(submitUnavailableMessage().contains("別の開催回を選んで"))
        #expect(retryUnavailableMessage().contains("別の開催回を選んで"))
        #expect(cancelUnavailableMessage().contains("予約は維持"))
    }

    @Test func 日時は日本時間で月日と曜日と時刻に整える() {
        #expect(formatStart(EpochMillis(1_798_675_200_000)) == "12月31日(木) 09:00")
        #expect(formatPrecise(EpochMillis(1_798_675_201_000)) == "12月31日(木) 09:00:01")
    }
}
