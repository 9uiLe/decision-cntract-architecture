// EXPECT: switch must be exhaustive
import EligibilityCore

func describe(_ result: Reverification) -> String {
    switch result {
    case .verified: return "verified"
    }
}
