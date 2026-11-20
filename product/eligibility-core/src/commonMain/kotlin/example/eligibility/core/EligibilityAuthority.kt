package example.eligibility.core

import example.kernel.ParticipantId
import kotlinx.coroutines.flow.StateFlow

// 資格を利用する Core が見る契約。差し替えられる型にして、別人や重複の証明書を返す負の対照を作れるようにする。
interface EligibilityAuthority {
    val snapshot: StateFlow<EligibilitySnapshot>

    fun status(participant: ParticipantId, kind: QualificationKind): EligibilityStatus

    fun request(participant: ParticipantId, kind: QualificationKind): EligibilityRequestAcceptance

    // 全員分を現在のサービスの観測で確かめ、全観測の後に共通の時刻で期限と対象を照合する。
    suspend fun verify(certificates: List<EligibilityCertificate>): Reverification
}
