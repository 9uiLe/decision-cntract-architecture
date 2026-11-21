// EXPECT: is inaccessible due to 'internal' protection level
import ReservationCore

func forge(draft: ConfirmedDraft) -> RetryTicket {
    RetryTicket(draft: draft)
}
