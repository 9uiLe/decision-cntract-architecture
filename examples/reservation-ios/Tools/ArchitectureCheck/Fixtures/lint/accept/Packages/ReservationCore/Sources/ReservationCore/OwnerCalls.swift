func owner(port: any ReservationPort) async {
    _ = await port.createReservation(request: id, session: session, participants: [])
    _ = await port.lookUpUsage(reservationId)
}
