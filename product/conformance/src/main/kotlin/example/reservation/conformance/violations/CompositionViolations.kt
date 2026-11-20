package example.reservation.conformance.violations

import example.kernel.EpochMillis
import example.reservation.core.Catalog
import example.reservation.core.RequestId
import example.reservation.core.ReservationCore
import example.reservation.core.ReservationPort
import example.reservation.core.createReservationCore as buildCore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope

fun buildCoreOutsideComposition(
    catalog: Catalog,
    port: ReservationPort,
    eligibility: example.eligibility.core.EligibilityAuthority,
    scope: CoroutineScope,
): ReservationCore =
    example.reservation.core.createReservationCore( // EXPECT: RestrictedCompositionCall
        catalog, port, eligibility, { EpochMillis(0) }, scope, { RequestId("x") }, 1.seconds,
    )

fun buildCoreThroughAlias(
    catalog: Catalog,
    port: ReservationPort,
    eligibility: example.eligibility.core.EligibilityAuthority,
    scope: CoroutineScope,
): ReservationCore =
    buildCore(catalog, port, eligibility, { EpochMillis(0) }, scope, { RequestId("x") }, 1.seconds) // EXPECT: RestrictedCompositionCall

val factoryReference = ::buildCore // EXPECT: RestrictedCompositionCall

fun buildOtherCoresOutsideComposition(
    eligibilityPort: example.eligibility.core.EligibilityPort,
    source: example.reservation.core.AdmissionSource,
    checkInPort: example.checkin.core.CheckInPort,
    scope: CoroutineScope,
) {
    example.eligibility.core.createEligibilityCore( // EXPECT: RestrictedCompositionCall
        eligibilityPort, { EpochMillis(0) }, scope, 1.seconds,
    )
    example.checkin.core.createCheckInCore( // EXPECT: RestrictedCompositionCall
        source, checkInPort, { EpochMillis(0) }, scope, { example.checkin.core.CheckInAttemptId("x") }, 1.seconds,
    )
}
