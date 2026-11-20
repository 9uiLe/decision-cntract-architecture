package contracts.reject

import example.eligibility.core.EligibilityAuthority
import example.kernel.Clock
import example.reservation.core.Catalog
import example.reservation.core.RequestId
import example.reservation.core.ReservationCore
import example.reservation.core.ReservationPort
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope

fun build(
    catalog: Catalog,
    port: ReservationPort,
    eligibility: EligibilityAuthority,
    clock: Clock,
    scope: CoroutineScope,
) = ReservationCore(catalog, port, eligibility, clock, { RequestId("x") }, scope, Duration.ZERO) // VIOLATION [INVISIBLE_REFERENCE]
