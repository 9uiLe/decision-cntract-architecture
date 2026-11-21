func call(port: Ports) async {
    _ = await port.eligibility.fetchQualification(participant: alice, kind: kind)  // EXPECT: eligibility_port_call
    _ = await port.reservation.createReservation(request: id, session: session, participants: [])  // EXPECT: reservation_port_call
    _ = await port.reservation.lookUpUsage(reservationId)  // EXPECT: reservation_port_call
    _ = await port.checkIn.createCheckIn(attempt: attempt, reservation: reservationId, participant: alice)  // EXPECT: check_in_port_call
}
