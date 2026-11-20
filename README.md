# Decision Contract Architecture

Decision Contract Architecture（DCA）は、仕様を変更したときに見直すべき判断を、Kotlin の型と依存関係で明示する設計です。

定員制ワークショップの予約要求に応答がなければ、予約はすでに成立しているかもしれません。Core は「結果不明」と「未成立が確定」を区別し、前者には元の要求を照会する操作、後者には再試行する操作を渡します。画面状態への変換を網羅的な `when` で書くと、新しい結果の分岐が抜けたときにコンパイラが検出します。

| 読む場所 | 内容 |
| --- | --- |
| [設計と検証](docs/README.md) | 判断契約、Android への適用、仕様変更の検証計画 |
| [運用ルール](rules/README.md) | 汎用の detekt ルールと予約アプリ固有の検査 |
| [Android の検証用アプリを実行](docs/android-product.md) | Jetpack Compose の予約アプリのビルド、実行、検査 |
| [Kotlin/JVM サンプルを実行](examples/README.md) | 最小の予約例で判断契約とコンパイル診断を確認 |
