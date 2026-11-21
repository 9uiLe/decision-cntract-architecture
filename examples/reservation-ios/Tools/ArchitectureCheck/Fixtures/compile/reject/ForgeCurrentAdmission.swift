// EXPECT: is inaccessible due to 'internal' protection level
import Kernel
import ReservationCore

func forge(record: ReservationAdmission) -> CurrentAdmission {
    CurrentAdmission(record: record, observedAt: EpochMillis(0))
}
