package example.reservation.conformance.violations

import example.kernel.IssuedEvidence

@IssuedEvidence
class OpenConstructedEvidence(val id: String) // EXPECT: IssuedEvidenceConstruction

@IssuedEvidence
data class CopyableEvidence internal constructor(val id: String) // EXPECT: IssuedEvidenceConstruction

@IssuedEvidence
class IssuedOnlyByOwner internal constructor(val id: String)

fun useWithDefault(evidence: IssuedOnlyByOwner = IssuedOnlyByOwner("x")): String = evidence.id // EXPECT: RequiredEvidenceParameter

fun useOptionally(evidence: IssuedOnlyByOwner?): String = evidence?.id.orEmpty() // EXPECT: RequiredEvidenceParameter

fun useRequired(evidence: IssuedOnlyByOwner): String = evidence.id

internal fun useInternally(evidence: IssuedOnlyByOwner? = null): String = evidence?.id.orEmpty()
