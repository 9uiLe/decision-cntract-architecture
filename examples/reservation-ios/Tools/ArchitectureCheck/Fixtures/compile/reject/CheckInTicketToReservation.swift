// EXPECT: cannot convert value of type 'CheckInStatusTicket' to expected argument type 'StatusCheckTicket'
import CheckInCore
import ReservationCore

func mix(core: ReservationCore, ticket: CheckInStatusTicket) {
    _ = core.checkStatus(ticket)
}
