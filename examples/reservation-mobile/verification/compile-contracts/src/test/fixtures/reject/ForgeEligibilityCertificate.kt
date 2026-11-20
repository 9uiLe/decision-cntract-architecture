package contracts.reject

import example.eligibility.core.EligibilityCertificate
import example.eligibility.core.QualificationKind
import example.kernel.EpochMillis
import example.kernel.ParticipantId

fun forge() = EligibilityCertificate(ParticipantId("a"), QualificationKind("k"), EpochMillis(0), EpochMillis(1)) // VIOLATION [INVISIBLE_REFERENCE]
