# 運用ルールと detekt の検査仕様

型と可視性で、業務操作に渡せる引数と生成経路を制限します。Gradle はモジュールの依存関係を検査します。detekt は、型検査を通っても必要な分岐や組み立ての制約を迂回するコードを検出します。

## DCA の汎用ルール

この表の検出条件は、別のプロダクトへ導入しても共通です。検査対象の関数、型、パッケージは導入先で指定します。

| ルール | 検出条件 | 許可するコード |
| --- | --- | --- |
| `ExhaustiveDecisionWhen` | 指定した関数の `when` が指定した型を対象とし、`else` を持つ | 全ケースを列挙した判断。外部サービスの未定義コードを扱う分岐 |
| `RestrictedCompositionCall` | 許可したパッケージの外で、指定の組み立て関数を呼び出すか、呼び出し可能参照を取得する | 組み立てを担当するコードからの呼び出し。別の同名関数の呼び出し |

シンボル解決によって、指定の型や関数を参照しているかを判定します。型エイリアスや import の別名があっても、参照先が同じなら検査対象です。許可パッケージは名前が一致するパッケージとその配下です。`example.app.composition` の許可は `example.app.compositionExtra` に及びません。[detekt の型解決](https://detekt.dev/docs/gettingstarted/type-resolution/)

## 予約アプリ固有の検査

予約アプリでは、予約の結果を画面状態に変換する関数、画面状態を描画する関数、Core を組み立てられるコードの場所を指定します。アプリを変更するときは、この適用先を実際の型や関数に合わせて更新します。

予約の作成・照会・取消は、予約 Core が状態と操作の前提を確認したうえでサービスに要求します。`reservation-contract` の [`ReservationPortCall`](../product/detekt-rules/src/main/kotlin/example/detekt/reservation/ReservationPortCall.kt) は、`ReservationPort` の `create`、`lookup`、`cancel` を予約 Core のパッケージ外から呼び出すか、呼び出し可能参照を取得すると報告します。別のプロダクトでは、その業務の Port と呼び出しを許す場所を定めます。

`ReservationPortCall` は呼び出し先の解決を汎用の実装と共有し、予約固有の対象を別のルールセットに置いています。二つの汎用ルールは `decision-contract` に登録しています。

適用先は [dca.yml](../product/config/detekt/dca.yml) に記載しています。`decisionFunctions` と `decisionTypes` が必須の判断箇所と型を指定し、`restrictedCallables` と `allowedCallerPackages` が組み立て関数と呼び出し元を指定します。

`product/` の `./gradlew check` は、Android の `detektDebug` と JVM の `detektMain` を使います。検証用の `:conformance` には意図した違反があり、`check` の出力にも診断が表示されます。テストは、検出されたルールと行が検証用コードに記した期待値と一致することを確かめます。設定で指定した型や関数の名前と、許可パッケージを宣言できるモジュールも照合します。

## 型・ビルド・実行時の確認

| 確認したいこと | 方法 |
| --- | --- |
| モジュール外で確認済みの値や操作の前提を構築できないこと | コンストラクタと発行処理の可視性を絞り、禁止した利用コードのコンパイルが失敗することを確認する |
| 必須 Handler の実装や供給に漏れがないこと | 委譲がある場合に、抽象メソッドと必須引数を使う |
| UI が外部 Adapter に直接依存できないこと | Gradle のモジュール依存を確認する。公開された組み立て関数への参照は detekt でも調べる |
| 利用側が必要な結果の違いを取得できること | `Boolean` や `null` にまとめる箇所は、受け取った側の判断まで追ってレビューする |
| 期限の境界、照会対象、要求回数が正しいこと | 時刻や外部サービスの応答を制御したテストで確かめる |

[Android の検証用アプリの実行手順](../docs/android-product.md)にビルドと検査のコマンドを記載しています。規約の設計根拠は、隣接する `usapo-type-driven-design` の `10-coding-principles.md` です。
