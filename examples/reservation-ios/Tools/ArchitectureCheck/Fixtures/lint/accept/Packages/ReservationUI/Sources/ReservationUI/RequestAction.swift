enum RequestAction: Equatable, Sendable {
    case lookUp(ByIdentity<String>)
}
