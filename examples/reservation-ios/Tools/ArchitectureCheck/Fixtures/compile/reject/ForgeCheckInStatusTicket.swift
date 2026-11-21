// EXPECT: is inaccessible due to 'internal' protection level
import CheckInCore
import ReservationCore

func forge(admission: ReservationAdmission) -> CheckInStatusTicket {
    CheckInStatusTicket(attempt: CheckInAttemptId("a"), admission: admission)
}
