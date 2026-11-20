package example.checkin.core

import example.kernel.ParticipantId
import example.reservation.core.ReservationId
import kotlin.jvm.JvmInline

// 受付の試行の識別子。予約作成の RequestId とは別の型で、照会に取り違えて渡せない。
@JvmInline value class CheckInAttemptId(val value: String)

sealed interface CheckInCreateObservation {
    data object Recorded : CheckInCreateObservation
    data object AlreadyRecorded : CheckInCreateObservation
    data object NotCreated : CheckInCreateObservation
    data object NoResponse : CheckInCreateObservation
}

sealed interface CheckInLookupObservation {
    data object Found : CheckInLookupObservation
    data object NotCreated : CheckInLookupObservation
    data object NotFound : CheckInLookupObservation
    data object Failed : CheckInLookupObservation
}

interface CheckInPort {
    suspend fun create(attempt: CheckInAttemptId, reservation: ReservationId, participant: ParticipantId): CheckInCreateObservation
    suspend fun lookup(attempt: CheckInAttemptId): CheckInLookupObservation
}
