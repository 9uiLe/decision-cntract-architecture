public import EligibilityCore
public import Kernel
public import ReservationCore
import SimulatorAdapter

private let hour: Int64 = 3_600_000

public let demoStart = EpochMillis(1_796_083_200_000)

public let potteryAdvancedCourse = QualificationKind(id: "pottery-advanced", name: "上級ろくろ修了")
public let roastingAdvancedCourse = QualificationKind(id: "roasting-advanced", name: "上級焙煎修了")

private func workshop(
    _ id: String, _ name: String, startsAfter hours: Int64, seats: Int, _ requirement: NewBookingRequirement = .notRequired
) -> Workshop {
    Workshop(
        id: SessionId(id), name: name, start: demoStart.advanced(byMillis: hours * hour), seatsHint: seats,
        requirement: requirement
    )
}

private let pottery = workshop("pottery", "はじめてのろくろ陶芸", startsAfter: 72, seats: 4)
private let cooking = workshop("cooking", "季節の家庭料理教室", startsAfter: 20, seats: 6)
private let coffee = workshop("coffee", "コーヒー豆の選び方講座", startsAfter: 2, seats: 2)
private let advancedPottery = workshop(
    "advanced-pottery", "上級ろくろ陶芸", startsAfter: 96, seats: 4, .required(potteryAdvancedCourse)
)
private let advancedRoasting = workshop(
    "advanced-roasting", "上級コーヒー焙煎", startsAfter: 120, seats: 4, .required(roastingAdvancedCourse)
)

public let akari = ParticipantId("akari")
public let kota = ParticipantId("kota")
public let minami = ParticipantId("minami")

public let demoCatalog = Catalog(
    workshops: [pottery, cooking, coffee, advancedPottery, advancedRoasting],
    participants: [
        Participant(id: akari, name: "佐藤 あかり"),
        Participant(id: kota, name: "鈴木 こうた"),
        Participant(id: minami, name: "田中 みなみ"),
    ]
)

// 表示する残席（seatsHint）は参考値で、サービス側の定員とは独立に定める。料理教室は表示より少ない定員で満席を再現する。
let demoCapacities: [SessionId: Int] = [
    pottery.id: 4, cooking.id: 2, coffee.id: 8, advancedPottery.id: 4, advancedRoasting.id: 4,
]

let demoHoldings: [QualificationHolding] = {
    let expiry = demoStart.advanced(byMillis: 30 * 24 * hour)
    return [
        QualificationHolding(participant: akari, kind: potteryAdvancedCourse, expiresAt: expiry),
        QualificationHolding(participant: akari, kind: roastingAdvancedCourse, expiresAt: expiry),
        QualificationHolding(participant: kota, kind: potteryAdvancedCourse, expiresAt: expiry),
    ]
}()
