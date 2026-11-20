package example.reservation.ui

import example.checkin.core.CheckInRejection
import example.checkin.core.Denial
import example.checkin.core.ProcessingStage
import example.eligibility.core.ReverificationFailure
import example.reservation.core.AdmissionUnavailableReason
import example.reservation.core.CancelFailure
import example.reservation.core.ConfirmOutcome
import example.reservation.core.CreateDenial
import example.reservation.core.Rejection

fun rejectionMessage(rejection: Rejection): String = when (rejection) {
    Rejection.UnknownWorkshop -> "開催回を選択してください。"
    Rejection.UnknownParticipant -> "選択した参加者を確認できませんでした。"
    Rejection.Stale -> "画面の内容が古くなっています。最新の状態を確認してください。"
    Rejection.Busy -> "処理中、または結果を確認できていない予約操作があります。先にその状況を確認してください。"
    Rejection.RegistrationClosed -> "この開催回は受付を終了しています。別の開催回を選んでください。"
    Rejection.CancellationClosed -> "取消期限（開始の24時間前）に達しているため取り消せません。予約は維持されています。"
    Rejection.QualificationNotRequired -> "この開催回では資格の確認は必要ありません。"
    Rejection.NoParticipants -> "参加者を選んでください。"
}

fun confirmFailureMessage(outcome: ConfirmOutcome): String? = when (outcome) {
    is ConfirmOutcome.Confirmed -> null
    ConfirmOutcome.NoWorkshop -> "開催回を選択してください。"
    ConfirmOutcome.EmptyParticipants -> "参加者を選んでください。"
    ConfirmOutcome.QualificationRequired -> "この開催回は資格が必要です。資格を確認してください。"
    ConfirmOutcome.QualificationNotRequired -> "この開催回では資格の確認は必要ありません。"
    is ConfirmOutcome.EligibilityNotMet -> "資格を満たしていない、または確認できていない参加者がいます。"
}

fun checkInRejectionMessage(rejection: CheckInRejection): String = when (rejection) {
    CheckInRejection.Busy -> "この参加者の受付を処理しています。結果を待ってください。"
    CheckInRejection.AlreadyCheckedIn -> "この参加者はすでに受付済みです。"
    CheckInRejection.Unresolved -> "前回の受付の結果を確認できていません。先に受付の状況を照会してください。"
    CheckInRejection.Stale -> "画面の内容が古くなっています。最新の状態を確認してください。"
    CheckInRejection.NotYetOpen -> "受付はまだ始まっていません。開始の30分前から受け付けます。"
    CheckInRejection.Closed -> "受付は終了しています。"
}

fun cancelFailureMessage(failure: CancelFailure): String = when (failure) {
    CancelFailure.Declined -> "取消が受け付けられませんでした。予約は維持されています。"
    CancelFailure.NoResponse -> "取消の結果を確認できませんでした。予約は維持されています。"
}

fun qualificationLabel(badge: QualificationBadge): String = when (badge) {
    QualificationBadge.NotChecked -> "未確認"
    QualificationBadge.Checking -> "確認中"
    QualificationBadge.Valid -> "資格あり"
    QualificationBadge.NotHeld -> "資格を取得していません"
    QualificationBadge.Expired -> "資格の有効期限が切れています"
    QualificationBadge.Unknown -> "資格を確認できませんでした"
}

fun eligibilityLostMessage(reason: ReverificationFailure): String = when (reason) {
    ReverificationFailure.NotHeld -> "送信の直前に資格を確認したところ、取得を確認できませんでした。"
    ReverificationFailure.Expired -> "送信の直前に資格を確認したところ、有効期限が切れていました。"
    ReverificationFailure.Unconfirmed -> "送信の直前に資格を確認できませんでした。"
    ReverificationFailure.TargetMismatch -> "資格の確認結果が参加者と一致しませんでした。"
}

fun createDenialMessage(denial: CreateDenial): String = when (denial) {
    CreateDenial.RegistrationClosed -> "再試行の確認中に開始時刻を迎え、受付が終了しました。"
    is CreateDenial.EligibilityLost -> eligibilityLostMessage(denial.reason)
}

fun requestHeadline(card: RequestCard): String = when (card) {
    is RequestCard.Sending -> "予約を送信しています"
    is RequestCard.CheckingStatus -> "予約の結果を照会しています"
    is RequestCard.Unknown -> "予約の結果を確認できていません"
    is RequestCard.NotCreated -> "予約は作成されていません"
    is RequestCard.SoldOut -> "満席で予約できませんでした"
    is RequestCard.RegistrationClosed -> "受付を終了した開催回です"
    is RequestCard.EligibilityLost -> "資格を満たしていないため送信しませんでした"
}

fun checkInStateLabel(view: CheckInView): String = when (view) {
    CheckInView.NotCheckedIn -> "未受付"
    is CheckInView.Processing -> processingLabel(view.stage)
    CheckInView.CheckedIn -> "受付済み"
    is CheckInView.Unknown -> "受付結果不明"
    is CheckInView.NotCreated -> "受付は成立していません"
    is CheckInView.NotAdmitted -> denialMessage(view.denial)
}

fun processingLabel(stage: ProcessingStage): String = when (stage) {
    ProcessingStage.ConfirmingReservation -> "予約を確認しています"
    ProcessingStage.Recording -> "受付を記録しています"
    ProcessingStage.LookingUp -> "受付の状況を照会しています"
}

fun denialMessage(denial: Denial): String = when (denial) {
    is Denial.ReservationUnavailable -> unavailableMessage(denial.reason)
    Denial.ReservationUnconfirmed -> "予約の状態を確認できなかったため、受付していません。"
    Denial.NotYetOpen -> "受付はまだ始まっていません。"
    Denial.Closed -> "受付は終了しています。"
    Denial.WrongTarget -> "予約と参加者の組み合わせを確認できませんでした。"
}

fun unavailableMessage(reason: AdmissionUnavailableReason): String = when (reason) {
    AdmissionUnavailableReason.NotIncluded -> "この予約に含まれない参加者です。"
    AdmissionUnavailableReason.NotReserved -> "成立が確認できた予約ではありません。"
    AdmissionUnavailableReason.Cancelled -> "取り消された予約のため受付できません。"
}
