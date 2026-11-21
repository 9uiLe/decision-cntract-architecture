enum RequestAction: Equatable {
    case lookUp(String)

    static func == (lhs: RequestAction, rhs: RequestAction) -> Bool { true }  // EXPECT: action_equality_by_identity
}

extension RequestAction: Hashable {}  // EXPECT: action_equality_by_identity
