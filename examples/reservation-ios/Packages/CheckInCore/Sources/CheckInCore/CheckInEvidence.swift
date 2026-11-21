public import Kernel
import ReservationCore

// 構築は CheckInCore だけが行う。元の試行と予約の対象は公開せず、操作の受付時に現在の状態と照合する。
public final class CheckInStatusTicket: IssuedEvidence {
    let attempt: CheckInAttemptId
    let admission: ReservationAdmission

    init(attempt: CheckInAttemptId, admission: ReservationAdmission) {
        self.attempt = attempt
        self.admission = admission
    }
}

public final class CheckInRetryTicket: IssuedEvidence {
    let admission: ReservationAdmission

    init(admission: ReservationAdmission) { self.admission = admission }
}
