// EXPECT: is inaccessible due to 'internal' protection level
import EligibilityCore
import Kernel
import ReservationCore

func forge(workshop: Workshop) -> ConfirmedDraft {
    ConfirmedDraft(workshop: workshop, participants: [ParticipantId("alice")], basis: .withoutQualification, certificates: [])
}
