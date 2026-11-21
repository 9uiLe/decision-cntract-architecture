func wire() {
    _ = makeReservationCore(catalog: catalog)
    _ = makeCheckInCore(source: source)
    _ = makeEligibilityCore(port: port)
}
