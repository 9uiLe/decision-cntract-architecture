package example.app.composition

import example.reservation.core.Catalog
import example.reservation.core.EpochMillis
import example.reservation.core.Participant
import example.reservation.core.Workshop
import example.reservation.ports.ParticipantId
import example.reservation.ports.SessionId
import java.time.Instant

private const val HOUR = 3_600_000L

val DemoStart: EpochMillis = EpochMillis(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli())

val Pottery = Workshop(SessionId("pottery"), "はじめてのろくろ陶芸", EpochMillis(DemoStart.value + 72 * HOUR), seatsHint = 4)
val Cooking = Workshop(SessionId("cooking"), "季節の家庭料理教室", EpochMillis(DemoStart.value + 20 * HOUR), seatsHint = 6)
val Coffee = Workshop(SessionId("coffee"), "コーヒー豆の選び方講座", EpochMillis(DemoStart.value + 2 * HOUR), seatsHint = 2)

// 表示する残席（seatsHint）は参考値で、サービス側の定員とは独立に定める。料理教室は表示より少ない定員で満席を再現する。
val DemoCapacities: Map<SessionId, Int> = mapOf(Pottery.id to 4, Cooking.id to 2, Coffee.id to 8)

val DemoCatalog = Catalog(
    workshops = listOf(Pottery, Cooking, Coffee),
    participants = listOf(
        Participant(ParticipantId("akari"), "佐藤 あかり"),
        Participant(ParticipantId("kota"), "鈴木 こうた"),
        Participant(ParticipantId("minami"), "田中 みなみ"),
    ),
)
