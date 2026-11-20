package example.reservation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import example.checkin.core.CheckInRetryTicket
import example.checkin.core.CheckInStatusTicket
import example.reservation.core.CancelTicket
import example.reservation.core.ReservationAdmission
import example.reservation.core.RetryTicket
import example.reservation.core.StatusCheckTicket

@Composable
fun ReservationStatusScreen(
    state: StatusState,
    onCheckStatus: (StatusCheckTicket) -> Unit,
    onRetry: (RetryTicket) -> Unit,
    onCancel: (CancelTicket) -> Unit,
    onCheckIn: (ReservationAdmission) -> Unit,
    onLookUpCheckIn: (CheckInStatusTicket) -> Unit,
    onRetryCheckIn: (CheckInRetryTicket) -> Unit,
    onChooseAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.request == null && state.reservations.isEmpty()) {
            Text("予約の操作はまだありません。", modifier = Modifier.testTag("status-empty"))
        }
        state.request?.let { RequestCardContent(it, onCheckStatus, onRetry, onChooseAgain) }
        state.reservations.forEach { ReservationCardContent(it, onCancel, onCheckIn, onLookUpCheckIn, onRetryCheckIn) }
    }
}

@Composable
fun RequestCardContent(
    card: RequestCard,
    onCheckStatus: (StatusCheckTicket) -> Unit,
    onRetry: (RetryTicket) -> Unit,
    onChooseAgain: () -> Unit,
) {
    when (card) {
        is RequestCard.Sending -> StatusCard(
            tag = "request-sending", label = "送信中", card.summary,
            description = "予約を送信しています。結果が届くまで、同じ予約を重ねて送信しません。",
        )
        is RequestCard.CheckingStatus -> StatusCard(
            tag = "request-checking", label = "照会中", card.summary,
            description = "元の予約要求の結果を確認しています。新しい予約は作成しません。",
        )
        is RequestCard.Unknown -> StatusCard(
            tag = "request-unknown", label = "結果不明", card.summary,
            description = "予約が成立している可能性があります。再試行はできません。元の要求を照会してください。",
            emphasis = Emphasis.Caution,
        ) {
            FilledTonalButton(
                onClick = { onCheckStatus(card.check) },
                modifier = Modifier.fillMaxWidth().testTag("lookup-button"),
            ) { Text("状況を照会する") }
        }
        is RequestCard.NotCreated -> StatusCard(
            tag = "request-not-created", label = "未成立が確定", card.summary,
            description = "予約は作成されていません。開催回の開始前なら、同じ内容でもう一度送信できます。" +
                (card.denial?.let { "\n${createDenialMessage(it)}" } ?: ""),
        ) {
            Button(
                onClick = { onRetry(card.retry) },
                modifier = Modifier.fillMaxWidth().testTag("retry-button"),
            ) { Text("再試行する") }
        }
        is RequestCard.SoldOut -> StatusCard(
            tag = "request-sold-out", label = "満席", card.summary,
            description = "定員に空きがなく、予約は成立していません。開催回や参加者を選び直してください。",
        ) {
            OutlinedButton(
                onClick = onChooseAgain,
                modifier = Modifier.fillMaxWidth().testTag("choose-again-button"),
            ) { Text("開催回を選び直す") }
        }
        is RequestCard.RegistrationClosed -> StatusCard(
            tag = "request-registration-closed", label = "受付終了", card.summary,
            description = "開始時刻を迎えたため、新規予約の受付は終了しています。予約要求は送信していません。別の開催回を選んでください。",
        ) {
            OutlinedButton(
                onClick = onChooseAgain,
                modifier = Modifier.fillMaxWidth().testTag("choose-another-button"),
            ) { Text("別の開催回を選ぶ") }
        }
        is RequestCard.EligibilityLost -> StatusCard(
            tag = "request-eligibility-lost", label = "資格の再確認が必要です", card.summary,
            description = "${eligibilityLostMessage(card.reason)}予約要求は送信していません。開催回を選んで、資格を確認し直してください。",
            emphasis = Emphasis.Caution,
        ) {
            OutlinedButton(
                onClick = onChooseAgain,
                modifier = Modifier.fillMaxWidth().testTag("choose-again-button"),
            ) { Text("開催回の一覧に戻る") }
        }
    }
}

@Composable
fun ReservationCardContent(
    card: ReservationCard,
    onCancel: (CancelTicket) -> Unit,
    onCheckIn: (ReservationAdmission) -> Unit,
    onLookUpCheckIn: (CheckInStatusTicket) -> Unit,
    onRetryCheckIn: (CheckInRetryTicket) -> Unit,
) {
    when (card) {
        is ReservationCard.Reserved -> StatusCard(
            tag = "reservation-${card.reservationId}", label = "予約確定", card.summary,
            description = "予約番号 ${card.reservationId}" +
                (card.lastCancelFailure?.let { "\n${cancelFailureMessage(it)}" } ?: ""),
            emphasis = Emphasis.Positive,
        ) {
            card.participants.forEach { row ->
                ParticipantCheckInContent(card.reservationId, row, onCheckIn, onLookUpCheckIn, onRetryCheckIn)
            }
            OutlinedButton(
                onClick = { onCancel(card.cancel) },
                modifier = Modifier.fillMaxWidth().testTag("cancel-button-${card.reservationId}"),
            ) { Text("予約を取り消す") }
        }
        is ReservationCard.Cancelling -> StatusCard(
            tag = "reservation-${card.reservationId}", label = "取消中", card.summary,
            description = "予約番号 ${card.reservationId} の取消結果を待っています。",
        )
        is ReservationCard.Cancelled -> StatusCard(
            tag = "reservation-${card.reservationId}", label = "取消済み", card.summary,
            description = "予約番号 ${card.reservationId} は取り消されました。履歴として表示しています。",
        )
    }
}

@Composable
private fun ParticipantCheckInContent(
    reservationId: String,
    row: ParticipantCheckInRow,
    onCheckIn: (ReservationAdmission) -> Unit,
    onLookUpCheckIn: (CheckInStatusTicket) -> Unit,
    onRetryCheckIn: (CheckInRetryTicket) -> Unit,
) {
    val tag = "${reservationId}-${row.admission.participant.value}"
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}.testTag("checkin-state-$tag")) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(checkInStateLabel(row.view), style = MaterialTheme.typography.bodyMedium)
        }
        when (val view = row.view) {
            CheckInView.NotCheckedIn, is CheckInView.NotAdmitted -> FilledTonalButton(
                onClick = { onCheckIn(row.admission) },
                modifier = Modifier.fillMaxWidth().testTag("checkin-button-$tag"),
            ) { Text("${row.name}を受付する") }
            is CheckInView.Unknown -> FilledTonalButton(
                onClick = { onLookUpCheckIn(view.check) },
                modifier = Modifier.fillMaxWidth().testTag("checkin-lookup-button-$tag"),
            ) { Text("${row.name}の受付状況を照会する") }
            is CheckInView.NotCreated -> FilledTonalButton(
                onClick = { onRetryCheckIn(view.retry) },
                modifier = Modifier.fillMaxWidth().testTag("checkin-retry-button-$tag"),
            ) { Text("${row.name}をもう一度受付する") }
            is CheckInView.Processing, CheckInView.CheckedIn -> Unit
        }
    }
}

private enum class Emphasis { Neutral, Positive, Caution }

@Composable
private fun StatusCard(
    tag: String,
    label: String,
    summary: BookingSummary,
    description: String,
    emphasis: Emphasis = Emphasis.Neutral,
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (emphasis) {
        Emphasis.Neutral -> colors.surfaceContainerLow to colors.onSurface
        Emphasis.Positive -> colors.primaryContainer to colors.onPrimaryContainer
        Emphasis.Caution -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.elevatedCardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(summary.workshopName, style = MaterialTheme.typography.bodyLarge)
            Text("${formatStart(summary.start)} 開始 ・ ${summary.participantNames.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
