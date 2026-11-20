package example.kernel

import kotlin.jvm.JvmInline

@JvmInline value class EpochMillis(val value: Long) : Comparable<EpochMillis> {
    override fun compareTo(other: EpochMillis): Int = value.compareTo(other.value)
}

fun interface Clock { fun now(): EpochMillis }
