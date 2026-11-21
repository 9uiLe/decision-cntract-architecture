// EXPECT: is inaccessible due to 'internal' protection level
import ReservationCore

func forge(draft: ConfirmedDraft) -> StatusCheckTicket {
    StatusCheckTicket(request: RequestId("r"), draft: draft)
}
