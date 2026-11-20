package example.eligibility.core

import example.kernel.ParticipantId
import kotlinx.coroutines.flow.StateFlow

// 資格を利用する Core が見る契約。差し替えられる型にして、別人や重複の証明書を返す負の対照を作れるようにする。
// 差し替えた実装は証明書を発行する側の責務を負わない。利用する Core は、返った証明書の対象と、判定時刻（checkedAt）が
// 今回の呼び出しの区間に入ることを自分で照合し、資格の期限は参照しない。型は古い証明書の再生を防がない。
interface EligibilityAuthority {
    val snapshot: StateFlow<EligibilitySnapshot>

    fun status(participant: ParticipantId, kind: QualificationKind): EligibilityStatus

    fun request(participant: ParticipantId, kind: QualificationKind): EligibilityRequestAcceptance

    // 全員分を現在のサービスの観測で確かめ、全観測の後に共通の時刻で期限と対象を照合する。
    suspend fun verify(certificates: List<EligibilityCertificate>): Reverification
}
