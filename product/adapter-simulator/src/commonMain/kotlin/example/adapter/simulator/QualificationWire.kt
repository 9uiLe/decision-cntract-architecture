package example.adapter.simulator

import example.eligibility.core.QualificationKind
import example.eligibility.core.QualificationObservation
import example.kernel.EpochMillis
import kotlin.time.Instant

// 資格サービスの外部データの形式。この形式を読み書きする処理はこのファイルに閉じる。
internal data class QualificationWire(val held: Boolean, val code: String?, val validUntil: String?)

internal object ServiceCodes {
    private val codes = mapOf(
        "pottery-advanced" to "POTTERY-ADV",
        "roasting-advanced" to "ROASTING-ADV",
    )

    fun codeFor(kind: QualificationKind): String? = codes[kind.id]
}

internal object QualificationWireFormat {
    fun render(wire: QualificationWire): String = buildList {
        add("held=${wire.held}")
        wire.code?.let { add("code=$it") }
        wire.validUntil?.let { add("valid_until=$it") }
    }.joinToString("&")

    // 解釈できない文字列は null。有効な資格とは推測しない。
    fun parse(text: String): QualificationWire? {
        val fields = text.split('&').associate { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) return null
            pair.substring(0, separator) to pair.substring(separator + 1)
        }
        val held = when (fields["held"]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return QualificationWire(held, fields["code"], fields["valid_until"])
    }
}

internal fun QualificationWire.toObservation(requested: QualificationKind): QualificationObservation {
    if (!held) return QualificationObservation.NotHeld
    if (code == null || code != ServiceCodes.codeFor(requested)) return QualificationObservation.Unavailable
    val expiresAt = validUntil?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        ?: return QualificationObservation.Unavailable
    return QualificationObservation.Held(EpochMillis(expiresAt))
}

internal object QualificationDecoder {
    fun decode(text: String, requested: QualificationKind): QualificationObservation =
        QualificationWireFormat.parse(text)?.toObservation(requested) ?: QualificationObservation.Unavailable
}

internal fun heldWire(kind: QualificationKind, expiresAt: EpochMillis): QualificationWire = QualificationWire(
    held = true,
    code = ServiceCodes.codeFor(kind),
    validUntil = Instant.fromEpochMilliseconds(expiresAt.value).toString(),
)
