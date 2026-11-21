public import EligibilityCore
public import Kernel
public import ReservationCore

public enum Delivery: Sendable { case respond, lose, hold }

public enum CreateEffect: Sendable { case create, soldOut, reject }

public struct CreateBehavior: Equatable, Sendable {
    public let effect: CreateEffect
    public let delivery: Delivery

    public static let succeed = CreateBehavior(effect: .create, delivery: .respond)
    public static let soldOut = CreateBehavior(effect: .soldOut, delivery: .respond)
    public static let rejectBeforeCreate = CreateBehavior(effect: .reject, delivery: .respond)
    public static let loseResponseAfterCreate = CreateBehavior(effect: .create, delivery: .lose)
    public static let loseResponseAfterReject = CreateBehavior(effect: .reject, delivery: .lose)
    public static let holdResponse = CreateBehavior(effect: .create, delivery: .hold)
}

extension CreateEffect: Equatable {}
extension Delivery: Equatable {}

public enum LookupBehavior: Sendable { case normal, notFound, fail, hold }

public enum CancelBehavior: Sendable { case normal, decline, lose, hold }

public enum UsageBehavior: Sendable { case normal, fail, hold }

public enum QualificationBehavior: Sendable { case normal, noResponse, unrecognized, hold }

public enum CheckInEffect: Sendable { case record, reject }

public struct CheckInBehavior: Equatable, Sendable {
    public let effect: CheckInEffect
    public let delivery: Delivery

    public static let succeed = CheckInBehavior(effect: .record, delivery: .respond)
    public static let rejectBeforeRecord = CheckInBehavior(effect: .reject, delivery: .respond)
    public static let loseResponseAfterRecord = CheckInBehavior(effect: .record, delivery: .lose)
    public static let holdResponse = CheckInBehavior(effect: .record, delivery: .hold)
}

extension CheckInEffect: Equatable {}

public struct QualificationHolding: Sendable {
    public let participant: ParticipantId
    public let kind: QualificationKind
    public let expiresAt: EpochMillis

    public init(participant: ParticipantId, kind: QualificationKind, expiresAt: EpochMillis) {
        self.participant = participant
        self.kind = kind
        self.expiresAt = expiresAt
    }
}

public struct CheckInRecordCount: Equatable, Sendable {
    public let reservation: ReservationId
    public let participant: ParticipantId
    public let records: Int
}

public struct SimulatorObservations: Equatable, Sendable {
    public var createRequests = 0
    public var lookUpRequests = 0
    public var lastLookedUpRequest: RequestId?
    public var cancelRequests = 0
    public var usageRequests = 0
    public var qualificationRequests = 0
    public var checkInCreateRequests = 0
    public var checkInLookUpRequests = 0
    public var checkInRepeatedForRecordedTarget = 0
    public var activeReservations = 0
    public var checkInRecords: [CheckInRecordCount] = []
    public var heldCalls = 0
    public var queuedCreate = 0
    public var queuedLookUp = 0
    public var queuedCancel = 0
    public var queuedUsage = 0
    public var queuedQualification = 0
    public var queuedCheckInCreate = 0
    public var queuedCheckInLookUp = 0
}

// Port とは別の面にする。検証用の入口には制御と観測だけを渡し、Port の呼び出しを直接させない。
public protocol SimulatorControls: Sendable {
    var observations: SimulatorObservations { get }
    func observationUpdates() -> AsyncStream<SimulatorObservations>
    func enqueueCreate(_ behavior: CreateBehavior)
    func enqueueLookUp(_ behavior: LookupBehavior)
    func enqueueCancel(_ behavior: CancelBehavior)
    func enqueueUsage(_ behavior: UsageBehavior)
    func enqueueQualification(_ behavior: QualificationBehavior)
    func enqueueCheckInCreate(_ behavior: CheckInBehavior)
    func enqueueCheckInLookUp(_ behavior: LookupBehavior)
    func grantQualification(participant: ParticipantId, kind: QualificationKind, expiresAt: EpochMillis)
    func revokeQualification(participant: ParticipantId, kind: QualificationKind)
    func releaseHeld()
    func reset()
}
