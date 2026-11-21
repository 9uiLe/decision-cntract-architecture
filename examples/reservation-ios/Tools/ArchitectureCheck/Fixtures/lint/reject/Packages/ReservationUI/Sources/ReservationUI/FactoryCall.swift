func build() {
    _ = makeReservationCore(catalog: catalog)  // EXPECT: restricted_factory_call
    let factory = makeCheckInCore  // EXPECT: restricted_factory_call
    _ = factory
}
