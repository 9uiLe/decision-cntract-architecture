func label(_ value: Int) -> String {
    switch value {
    case 0: return "zero"
    default: return "other"  // EXPECT: no_default_in_switch
    }
}

func catchAll(_ value: Int) -> String {
    switch value {
    case 0: return "zero"
    case _: return "other"  // EXPECT: no_default_in_switch
    }
}

func unknown(_ style: SystemStyle) -> String {
    switch style {
    case .light: return "light"
    @unknown default: return "other"  // EXPECT: no_default_in_switch
    }
}
