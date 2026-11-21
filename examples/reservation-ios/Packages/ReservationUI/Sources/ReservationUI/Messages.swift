public import CheckInCore
public import EligibilityCore
public import ReservationCore

public func rejectionMessage(_ rejection: Rejection) -> String {
    switch rejection {
    case .unknownWorkshop: return "開催回を選択してください。"
    case .unknownParticipant: return "選択した参加者を確認できませんでした。"
    case .stale: return "画面の内容が古くなっています。最新の状態を確認してください。"
    case .busy: return "処理中、または結果を確認できていない予約操作があります。先にその状況を確認してください。"
    case .registrationClosed: return "この開催回は受付を終了しています。別の開催回を選んでください。"
    case .cancellationClosed: return "取消期限（開始の24時間前）に達しているため取り消せません。予約は維持されています。"
    case .qualificationNotRequired: return "この開催回では資格の確認は必要ありません。"
    case .noParticipants: return "参加者を選んでください。"
    }
}

public func confirmFailureMessage(_ outcome: ConfirmOutcome) -> String? {
    switch outcome {
    case .confirmed: return nil
    case .noWorkshop: return "開催回を選択してください。"
    case .emptyParticipants: return "参加者を選んでください。"
    case .qualificationRequired: return "この開催回は資格が必要です。資格を確認してください。"
    case .qualificationNotRequired: return "この開催回では資格の確認は必要ありません。"
    case .eligibilityNotMet: return "資格を満たしていない、または確認できていない参加者がいます。"
    }
}

public func checkInRejectionMessage(_ rejection: CheckInRejection) -> String {
    switch rejection {
    case .busy: return "この参加者の受付を処理しています。結果を待ってください。"
    case .alreadyCheckedIn: return "この参加者はすでに受付済みです。"
    case .unresolved: return "前回の受付の結果を確認できていません。先に受付の状況を照会してください。"
    case .stale: return "画面の内容が古くなっています。最新の状態を確認してください。"
    case .notYetOpen: return "受付はまだ始まっていません。開始の30分前から受け付けます。"
    case .closed: return "受付は終了しています。"
    }
}

public func cancelFailureMessage(_ failure: CancelFailure) -> String {
    switch failure {
    case .declined: return "取消が受け付けられませんでした。予約は維持されています。"
    case .noResponse: return "取消の結果を確認できませんでした。予約は維持されています。"
    }
}

public func qualificationLabel(_ badge: QualificationBadge) -> String {
    switch badge {
    case .notChecked: return "未確認"
    case .checking: return "確認中"
    case .valid: return "資格あり"
    case .notHeld: return "資格を取得していません"
    case .expired: return "資格の有効期限が切れています"
    case .unknown: return "資格を確認できませんでした"
    }
}

public func eligibilityLostMessage(_ reason: ReverificationFailure) -> String {
    switch reason {
    case .notHeld: return "送信の直前に資格を確認したところ、取得を確認できませんでした。"
    case .expired: return "送信の直前に資格を確認したところ、有効期限が切れていました。"
    case .unconfirmed: return "送信の直前に資格を確認できませんでした。"
    case .targetMismatch: return "資格の確認結果が参加者と一致しませんでした。"
    }
}

public func createDenialMessage(_ denial: CreateDenial) -> String {
    switch denial {
    case .registrationClosed: return "再試行の確認中に開始時刻を迎え、受付が終了しました。"
    case .eligibilityLost(let reason): return eligibilityLostMessage(reason)
    }
}

public func requestHeadline(_ card: RequestCard) -> String {
    switch card {
    case .sending: return "予約を送信しています"
    case .checkingStatus: return "予約の結果を照会しています"
    case .unknown: return "予約の結果を確認できていません"
    case .notCreated: return "予約は作成されていません"
    case .soldOut: return "満席で予約できませんでした"
    case .registrationClosed: return "受付を終了した開催回です"
    case .eligibilityLost: return "資格を満たしていないため送信しませんでした"
    }
}

public func checkInStateLabel(_ view: CheckInView) -> String {
    switch view {
    case .notCheckedIn: return "未受付"
    case .processing(let stage): return processingLabel(stage)
    case .checkedIn: return "受付済み"
    case .unknown: return "受付結果不明"
    case .notCreated: return "受付は成立していません"
    case .notAdmitted(let denial): return denialMessage(denial)
    }
}

public func processingLabel(_ stage: ProcessingStage) -> String {
    switch stage {
    case .confirmingReservation: return "予約を確認しています"
    case .recording: return "受付を記録しています"
    case .lookingUp: return "受付の状況を照会しています"
    }
}

public func denialMessage(_ denial: Denial) -> String {
    switch denial {
    case .reservationUnavailable(let reason): return unavailableMessage(reason)
    case .reservationUnconfirmed: return "予約の状態を確認できなかったため、受付していません。"
    case .notYetOpen: return "受付はまだ始まっていません。"
    case .closed: return "受付は終了しています。"
    case .wrongTarget: return "予約と参加者の組み合わせを確認できませんでした。"
    }
}

public func unavailableMessage(_ reason: AdmissionUnavailableReason) -> String {
    switch reason {
    case .notIncluded: return "この予約に含まれない参加者です。"
    case .notReserved: return "成立が確認できた予約ではありません。"
    case .cancelled: return "取り消された予約のため受付できません。"
    }
}

public func submitUnavailableMessage() -> String {
    "開始時刻を迎えたため、この開催回の予約は送信できません。開催回の一覧から別の開催回を選んでください。"
}

public func retryUnavailableMessage() -> String {
    "開始時刻を迎えたため、再試行できません。予約は作成されていません。開催回の一覧から別の開催回を選んでください。"
}

public func cancelUnavailableMessage() -> String {
    "取消期限（開始の24時間前）を過ぎたため、取り消せません。予約は維持されています。"
}

public func checkInWindowMessage(_ window: CheckInWindow) -> String? {
    switch window {
    case .notYetOpen: return "受付は開始の30分前から行えます。"
    case .open: return nil
    case .closed: return "受付は終了しています。"
    }
}
