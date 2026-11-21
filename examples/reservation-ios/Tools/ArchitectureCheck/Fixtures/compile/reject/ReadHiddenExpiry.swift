// EXPECT: is inaccessible due to 'internal' protection level
import EligibilityCore

func expiry(of certificate: EligibilityCertificate) -> Int64 {
    certificate.expiresAt.value
}
