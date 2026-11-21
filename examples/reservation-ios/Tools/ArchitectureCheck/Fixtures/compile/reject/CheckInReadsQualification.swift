// EXPECT: is not available due to missing import of defining module 'EligibilityCore'
import ReservationCore

func qualificationName(of admission: ReservationAdmission) -> String? {
    if case .withQualification(let kind) = admission.basis { return kind.id }
    return nil
}
