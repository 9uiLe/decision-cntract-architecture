public import ReservationCore

// 予約状況カードの操作。投影は券だけを保持し、画面の接続点で実行先と束ねて作る。
// 同じ case が実行先と券の両方を保持し、比較にも実行にも同じ値を使う。
public enum RequestAction: Equatable, Sendable {
    case lookUp(ByIdentity<ReservationViewModel>, ByIdentity<StatusCheckTicket>)
    case retry(ByIdentity<ReservationViewModel>, ByIdentity<RetryTicket>)

    public static func lookingUp(_ ticket: StatusCheckTicket, on endpoint: ReservationViewModel) -> RequestAction {
        .lookUp(ByIdentity(endpoint), ByIdentity(ticket))
    }

    public static func retrying(_ ticket: RetryTicket, on endpoint: ReservationViewModel) -> RequestAction {
        .retry(ByIdentity(endpoint), ByIdentity(ticket))
    }

    @MainActor
    @discardableResult
    public func execute() -> Acceptance {
        switch self {
        case .lookUp(let endpoint, let ticket): return endpoint.object.checkStatus(ticket.object)
        case .retry(let endpoint, let ticket): return endpoint.object.retry(ticket.object)
        }
    }
}
