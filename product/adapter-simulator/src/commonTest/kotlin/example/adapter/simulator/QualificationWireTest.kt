package example.adapter.simulator

import example.eligibility.core.QualificationKind
import example.eligibility.core.QualificationObservation
import example.kernel.EpochMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val pottery = QualificationKind("pottery-advanced")
private val roasting = QualificationKind("roasting-advanced")
private const val POTTERY_ADV_UNTIL = "2026-12-10T00:00:00Z"
private const val POTTERY_ADV_UNTIL_MILLIS = 1_796_860_800_000L

class QualificationWireTest {
    @Test
    fun `固定した入力を型付きの外部データに解析し、期限の事実として観測に変換する`() {
        val text = "held=true&code=POTTERY-ADV&valid_until=$POTTERY_ADV_UNTIL"
        assertEquals(QualificationWire(true, "POTTERY-ADV", POTTERY_ADV_UNTIL), QualificationWireFormat.parse(text))
        assertEquals(
            QualificationObservation.Held(EpochMillis(POTTERY_ADV_UNTIL_MILLIS)),
            QualificationDecoder.decode(text, pottery),
        )
        assertEquals(QualificationObservation.NotHeld, QualificationDecoder.decode("held=false", pottery))
    }

    @Test
    fun `未知のコード・別の種別のコード・欠けた期限・形式の崩れは、有効な資格と推測しない`() {
        assertEquals(
            QualificationObservation.Unavailable,
            QualificationDecoder.decode("held=true&code=UNKNOWN-01&valid_until=$POTTERY_ADV_UNTIL", pottery),
        )
        assertEquals(
            QualificationObservation.Unavailable,
            QualificationDecoder.decode("held=true&code=POTTERY-ADV&valid_until=$POTTERY_ADV_UNTIL", roasting),
        )
        assertEquals(QualificationObservation.Unavailable, QualificationDecoder.decode("held=true&code=POTTERY-ADV", pottery))
        assertEquals(
            QualificationObservation.Unavailable,
            QualificationDecoder.decode("held=true&code=POTTERY-ADV&valid_until=いつか", pottery),
        )
        assertEquals(QualificationObservation.Unavailable, QualificationDecoder.decode("unrecognized-response", pottery))
        assertEquals(QualificationObservation.Unavailable, QualificationDecoder.decode("held=maybe", pottery))
        assertNull(QualificationWireFormat.parse(""))
    }

    @Test
    fun `書き出した外部データは同じ内容に解析される`() {
        val wire = heldWire(pottery, EpochMillis(POTTERY_ADV_UNTIL_MILLIS))
        assertEquals(wire, QualificationWireFormat.parse(QualificationWireFormat.render(wire)))
    }
}
