package example.app.composition

import example.adapter.simulator.SimulatorControls
import example.adapter.simulator.createSimulator
import example.checkin.core.CheckInAttemptId
import example.checkin.core.CheckInCore
import example.checkin.core.createCheckInCore
import example.eligibility.core.EligibilityCore
import example.eligibility.core.createEligibilityCore
import example.reservation.core.Catalog
import example.reservation.core.RequestId
import example.reservation.core.ReservationCore
import example.reservation.core.createReservationCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Core と Port 実装の接続はここだけで行う。検証用の入口には Port ではなく制御と観測の面だけを渡す。
// 接続は結果の意味を保って渡すだけで、業務の判断は各 Core が持つ。
@OptIn(ExperimentalUuidApi::class)
class AppContainer {
    val catalog: Catalog = DemoCatalog
    val clock = ControllableClock(DemoStart)

    private val simulator = createSimulator(DemoCapacities, DemoHoldings)
    val simulatorControls: SimulatorControls = simulator.controls

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val eligibility: EligibilityCore = createEligibilityCore(
        port = simulator.eligibility,
        clock = clock,
        scope = scope,
        operationTimeout = 20.seconds,
    )

    val core: ReservationCore = createReservationCore(
        catalog = catalog,
        port = simulator.reservation,
        eligibility = eligibility,
        clock = clock,
        scope = scope,
        newRequestId = { RequestId(Uuid.random().toString()) },
        operationTimeout = 20.seconds,
    )

    val checkIn: CheckInCore = createCheckInCore(
        source = core,
        port = simulator.checkIn,
        clock = clock,
        scope = scope,
        newAttemptId = { CheckInAttemptId(Uuid.random().toString()) },
        operationTimeout = 20.seconds,
    )
}
