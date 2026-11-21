func label(_ value: Bool) -> String {
    switch value {
    case true: return "yes"
    case false: return "no"
    }
}

func pair(_ first: Bool, _ second: Bool) -> String {
    switch (first, second) {
    case (true, true): return "both"
    case (true, false), (false, true): return "one"
    case (false, false): return "none"
    }
}
