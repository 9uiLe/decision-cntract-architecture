public import CheckInCore
public import EligibilityCore
import Foundation
import Kernel
public import ReservationCore
public import SimulatorAdapter

// Core と Port 実装の接続はここだけで行う。検証用の入口には Port ではなく制御と観測の面だけを渡す。
public final class AppContainer: Sendable {
    public let catalog: Catalog
    public let clock: ControllableClock
    public let simulatorControls: any SimulatorControls
    public let eligibility: EligibilityCore
    public let core: ReservationCore
    public let checkIn: CheckInCore

    public init() {
        let catalog = demoCatalog
        let clock = ControllableClock(demoStart)
        let simulator = makeSimulator(capacities: demoCapacities, holdings: demoHoldings)
        let timeout = WallClockTimeout(.seconds(20))
        let eligibility = makeEligibilityCore(port: simulator.eligibility, clock: clock, timeout: timeout)
        let core = makeReservationCore(
            catalog: catalog,
            port: simulator.reservation,
            eligibility: eligibility,
            clock: clock,
            newRequestId: { RequestId(UUID().uuidString) },
            timeout: timeout
        )
        self.catalog = catalog
        self.clock = clock
        simulatorControls = simulator.controls
        self.eligibility = eligibility
        self.core = core
        checkIn = makeCheckInCore(
            source: core,
            port: simulator.checkIn,
            clock: clock,
            newAttemptId: { CheckInAttemptId(UUID().uuidString) },
            timeout: timeout
        )
    }
}
