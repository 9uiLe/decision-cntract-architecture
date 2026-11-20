package example.reservation.ui

import example.reservation.core.CancelFailure
import example.reservation.core.Rejection

fun rejectionMessage(rejection: Rejection): String = when (rejection) {
    Rejection.UnknownWorkshop -> "開催回を選択してください。"
    Rejection.UnknownParticipant -> "選択した参加者を確認できませんでした。"
    Rejection.Stale -> "画面の内容が古くなっています。最新の状態を確認してください。"
    Rejection.Busy -> "処理中、または結果を確認できていない予約操作があります。先にその状況を確認してください。"
    Rejection.RegistrationClosed -> "この開催回は受付を終了しています。別の開催回を選んでください。"
    Rejection.CancellationClosed -> "取消期限（開始の24時間前）を過ぎているため取り消せません。予約は維持されています。"
}

fun cancelFailureMessage(failure: CancelFailure): String = when (failure) {
    CancelFailure.Declined -> "取消が受け付けられませんでした。予約は維持されています。"
    CancelFailure.NoResponse -> "取消の結果を確認できませんでした。予約は維持されています。"
}

fun requestHeadline(card: RequestCard): String = when (card) {
    is RequestCard.Sending -> "予約を送信しています"
    is RequestCard.CheckingStatus -> "予約の結果を照会しています"
    is RequestCard.Unknown -> "予約の結果を確認できていません"
    is RequestCard.NotCreated -> "予約は作成されていません"
    is RequestCard.SoldOut -> "満席で予約できませんでした"
    is RequestCard.RegistrationClosed -> "受付を終了した開催回です"
}
