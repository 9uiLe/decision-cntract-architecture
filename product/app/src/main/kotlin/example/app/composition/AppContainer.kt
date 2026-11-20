package example.app.composition

import example.adapter.simulator.SimulatorControls
import example.adapter.simulator.createSimulator
import example.reservation.core.Catalog
import example.reservation.core.ReservationCore
import example.reservation.core.createReservationCore
import example.reservation.ports.RequestId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

// Core と Port 実装の接続はここだけで行う。検証用の入口には Port ではなく制御と観測の面だけを渡す。
class AppContainer {
    val catalog: Catalog = DemoCatalog
    val clock = ControllableClock(DemoStart)

    private val simulator = createSimulator(DemoCapacities)
    val simulatorControls: SimulatorControls = simulator.controls

    val core: ReservationCore = createReservationCore(
        catalog = catalog,
        port = simulator.port,
        clock = clock,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        newRequestId = { RequestId(UUID.randomUUID().toString()) },
        operationTimeout = 20.seconds,
    )
}
