package example.reservation.conformance.violations

import example.kernel.BoundAction

@BoundAction
data class BoundOnlyByConstructor(val endpoint: Any, val ticket: Any)

@BoundAction
class NotComparedByComponents(val endpoint: Any, val ticket: Any) // EXPECT: BoundActionEquality

@BoundAction
data class ReassignableTicket(val endpoint: Any, var ticket: Any) // EXPECT: BoundActionEquality

@BoundAction
data class IgnoresTicketInEquals(val endpoint: Any, val ticket: Any) {
    override fun equals(other: Any?): Boolean = other is IgnoresTicketInEquals && other.endpoint === endpoint // EXPECT: BoundActionEquality

    override fun hashCode(): Int = endpoint.hashCode() // EXPECT: BoundActionEquality
}

@BoundAction
data class KeepsStateOutside(val endpoint: Any, val ticket: Any) {
    var held: Any? = null // EXPECT: BoundActionEquality
}
