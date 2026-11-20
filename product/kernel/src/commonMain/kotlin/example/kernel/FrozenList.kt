package example.kernel

// 保証を運ぶ公開リストは、Kotlin の List 型のまま MutableList へキャストしても変更できないようにする。
fun <T> List<T>.frozen(): List<T> = if (this is FrozenList<T>) this else FrozenList(toList())

private class FrozenList<T>(private val items: List<T>) : AbstractList<T>() {
    override val size: Int get() = items.size

    override fun get(index: Int): T = items[index]
}
