// EXPECT: is inaccessible due to 'internal' protection level
import EligibilityCore
import Kernel

func forge() -> EligibilityCertificate {
    EligibilityCertificate(
        participant: ParticipantId("alice"), kind: QualificationKind(id: "k"), checkedAt: EpochMillis(0), expiresAt: EpochMillis(1)
    )
}
