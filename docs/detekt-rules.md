# 運用ルールと detekt の検査仕様

型と可視性で、業務操作に渡せる引数と生成経路を制限します。モジュールの依存宣言と import は `:conformance` のテストが照合します。detekt は、型検査を通っても必要な分岐や組み立ての制約を迂回するコードを検出します。

## DCA の汎用ルール

この表の検出条件は、別のプロダクトへ導入しても共通です。検査対象の関数、型、パッケージは導入先で指定します。[カスタムルールの実装](../examples/reservation-mobile/verification/detekt-rules/)

| ルール | 検出条件 | 許可するコード |
| --- | --- | --- |
| `ExhaustiveDecisionWhen` | 指定した注釈の付いた型を対象とする `when` が `else` を持つ | 全ケースを列挙した判断。外部サービスの未定義コードを扱う分岐 |
| `IssuedEvidenceConstruction` | 指定した注釈が付いた `data class`、または同じ注釈が付いた非 `sealed` クラスの `open`・`abstract` 宣言や公開コンストラクタ | `internal` または `private` コンストラクタを持つ通常のクラス。派生型が閉じた `sealed` 階層 |
| `RequiredEvidenceParameter` | 発行の対象として指定した型を受け取る公開操作で、その引数が既定値や `null` を許す | 呼び出し時に確認済みの値を要求する公開操作。内部状態で保持する任意の値 |
| `RestrictedCompositionCall` | 許可したパッケージの外で、指定の組み立て関数を呼び出すか、呼び出し可能参照を取得する | 組み立てを担当するコードからの呼び出し。別の同名関数の呼び出し |

シンボル解決によって、指定の型や関数を参照しているかを判定します。型エイリアスや import の別名があっても、参照先が同じなら検査対象です。許可パッケージは名前が一致するパッケージとその配下です。`example.app.composition` の許可は `example.app.compositionExtra` に及びません。[detekt の型解決](https://detekt.dev/docs/gettingstarted/type-resolution/)

注釈を付けられない型は、`ExhaustiveDecisionWhen` の `decisionTypes` に完全修飾名を指定できます。`decisionFunctions` で絞り込めるのは、`decisionTypes` で指定した型を扱う関数です。指定した注釈を持つ型は、関数の設定に関係なく検査します。

## 検証アプリ固有の検査

`reservation-contract` は、各 Port を呼び出せる Core を指定します。呼び出しや呼び出し可能参照をシンボルから解決する処理は、汎用ルールと共有します。別のプロダクトへ導入するときは、その業務の Port、操作、所有 Core を指定します。

| ルール | 検査する呼び出し | 呼び出せる場所 |
| --- | --- | --- |
| `EligibilityPortCall` | 資格の照会 | 参加資格 Core |
| `ReservationPortCall` | 予約の作成、照会、取消、現在状態の照会 | 予約 Core |
| `CheckInPortCall` | 受付の作成と照会 | 来場受付 Core |

[dca.yml](../examples/reservation-mobile/verification/config/detekt/dca.yml) では、網羅性が必要な型に使う `@DecisionResult` と、発行を制限する型に使う `@IssuedEvidence` を指定します。宣言に付けた印を型解決で読むため、資格に基づく予約判断や画面への変換の関数名を登録する作業が不要になります。組み立ての呼び出し元は `restrictedCallables` と `allowedCallerPackages` で指定します。

網羅性の規約が調べるのは、対象式を持つ `when` の `else` です。課題 H・J では、対応漏れを Kotlin の診断でも確かめます。構築の規約はクラスの宣言を検査します。比較 C3 では、課題 I の要資格受付で証明書を省略できる互換操作を加え、利用コードのコンパイル結果から制約が崩れたことを確かめます。[比較 C1〜C3](experiments/contract-comparisons.md)

`./gradlew check` は、Android と JVM の型解決付きタスクを実行します。KMP の `detektMainJvm` と `detektMainAndroid` は `commonMain` も検査します。iOS の固有コードは iOS ビルドで確認します。`:conformance` の診断には検査用の違反が含まれ、テストはルール名と診断行を照合します。許可した組み立てパッケージの所有モジュール、Gradle の依存宣言、Core と UI の import を同じ検査で確認します。特に基準仕様では、来場受付 Core から参加資格 Core への参照を禁止します。

## 型・ビルド・実行時の確認

| 確認したいこと | 方法 |
| --- | --- |
| モジュール外で確認済みの値や操作の前提を構築できないこと | コンストラクタと発行処理の可視性を絞り、禁止した利用コードのコンパイルが失敗することを確認する |
| 必須 Handler の実装や供給に漏れがないこと | 委譲がある場合に、抽象メソッドと必須引数を使う |
| UI が外部 Adapter に直接依存できないこと | Gradle のモジュール依存を確認する。公開された組み立て関数への参照は detekt でも調べる |
| 利用側が必要な結果の違いを取得できること | `Boolean` や `null` にまとめる箇所は、受け取った側の判断まで追ってレビューする |
| 期限の境界、照会対象、要求回数が正しいこと | 時刻や外部サービスの応答を制御したテストで確かめる |

モバイル版で各ルールを適用する箇所は図で確認できます。 [detekt の図解](../examples/reservation-mobile/docs/README.md#detekt) · [検証アプリの実行手順](mobile-app/setup.md) · [設計思想と判断契約](architecture.md)
