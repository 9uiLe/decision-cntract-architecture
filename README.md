# Decision Contract Architecture

Decision Contract Architecture（DCA）は、仕様を変更したときに見直すべき判断を、Kotlin の型と依存関係で明示する設計です。

定員制ワークショップの予約要求に応答がなければ、予約はすでに成立しているかもしれません。Core は「結果不明」と「未成立が確定」を区別し、前者には元の要求を照会する操作、後者には再試行する操作を渡します。画面状態への変換を網羅的な `when` で書くと、新しい結果の分岐が抜けたときにコンパイラが検出します。

検証アプリでは、参加資格、予約、来場受付を独立した Core に分けています。Android と iOS の共通画面を使い、資格や予約の契約を変更したとき、どの判断に対応が必要かを確かめられます。

| 読む場所 | 内容 |
| --- | --- |
| [設計と検証](docs/README.md) | 判断契約、3つの Core、仕様変更の検証 |
| [運用ルール](docs/detekt-rules.md) | 汎用の detekt ルールと予約アプリ固有の検査 |
| [検証アプリを実行](docs/mobile-app/setup.md) | Android・iOS の予約・来場受付アプリのビルド、実行、検査 |
| [サンプルコード](examples/README.md) | Kotlin/JVM の最小例と Android・iOS の検証アプリのソース |
