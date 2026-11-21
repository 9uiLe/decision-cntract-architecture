package example.kernel

// 画面の接続点で、操作の実行先（endpoint）と操作券を一つに束ねた値の印。
// 比較は構成要素すべての同一性で決まる。表示が同じでも、券か実行先が違えば別の action になる。
@Target(AnnotationTarget.CLASS)
annotation class BoundAction
