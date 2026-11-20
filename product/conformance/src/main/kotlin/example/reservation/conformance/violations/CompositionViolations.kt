package example.reservation.conformance.violations

import example.reservation.core.Catalog
import example.reservation.core.EpochMillis
import example.reservation.core.ReservationCore
import example.reservation.core.createReservationCore as buildCore
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationPort
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds

fun buildCoreOutsideComposition(catalog: Catalog, port: ReservationPort, scope: CoroutineScope): ReservationCore =
    example.reservation.core.createReservationCore( // EXPECT: RestrictedCompositionCall
        catalog, port, { EpochMillis(0) }, scope, { RequestId("x") }, 1.seconds,
    )

fun buildCoreThroughAlias(catalog: Catalog, port: ReservationPort, scope: CoroutineScope): ReservationCore =
    buildCore(catalog, port, { EpochMillis(0) }, scope, { RequestId("x") }, 1.seconds) // EXPECT: RestrictedCompositionCall

val factoryReference = ::buildCore // EXPECT: RestrictedCompositionCall
