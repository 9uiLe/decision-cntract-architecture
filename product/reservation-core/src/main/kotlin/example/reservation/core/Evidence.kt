package example.reservation.core

import example.reservation.ports.ParticipantId
import example.reservation.ports.RequestId
import example.reservation.ports.ReservationId

// 証拠型は data class にしない。copy で別の内容へ付け替えられないようにするため。
// 構築は Core だけが行い、操作の受付時に同一性（===）で「いま有効な発行物」と照合される。
class ConfirmedDraft internal constructor(
    val workshop: Workshop,
    val participants: List<ParticipantId>,
)

class StatusCheckTicket internal constructor(
    val request: RequestId,
    val draft: ConfirmedDraft,
)

class RetryTicket internal constructor(
    val draft: ConfirmedDraft,
)

class CancelTicket internal constructor(
    val reservation: ReservationId,
)
