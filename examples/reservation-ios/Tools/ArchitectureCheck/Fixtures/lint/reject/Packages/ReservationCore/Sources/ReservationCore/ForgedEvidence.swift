public final class Ticket: IssuedEvidence {
    public init() {}  // EXPECT: evidence_construction
}

open class OpenTicket: IssuedEvidence {  // EXPECT: evidence_construction
}
