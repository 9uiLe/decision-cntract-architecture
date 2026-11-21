// EXPECT: is inaccessible due to 'internal' protection level
import CheckInCore
import ReservationCore

func forge(admission: ReservationAdmission) -> CheckInRetryTicket {
    CheckInRetryTicket(admission: admission)
}
