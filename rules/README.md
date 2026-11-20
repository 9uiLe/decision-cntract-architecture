# 運用ルールと detekt の検査仕様

型と可視性で、業務操作に渡せる引数と生成経路を制限します。Gradle はモジュールの依存関係を検査します。detekt は、型検査を通っても必要な分岐や組み立ての制約を迂回するコードを検出します。

## DCA の汎用ルール

この表の検出条件は、別のプロダクトへ導入しても共通です。検査対象の関数、型、パッケージは導入先で指定します。

| ルール | 検出条件 | 許可するコード |
| --- | --- | --- |
| `ExhaustiveDecisionWhen` | 指定した関数の `when` が指定した型を対象とし、`else` を持つ | 全ケースを列挙した判断。外部サービスの未定義コードを扱う分岐 |
| `RestrictedCompositionCall` | 許可したパッケージの外で、指定の組み立て関数を呼び出すか、呼び出し可能参照を取得する | 組み立てを担当するコードからの呼び出し。別の同名関数の呼び出し |

シンボル解決によって、指定の型や関数を参照しているかを判定します。型エイリアスや import の別名があっても、参照先が同じなら検査対象です。許可パッケージは名前が一致するパッケージとその配下です。`example.app.composition` の許可は `example.app.compositionExtra` に及びません。[detekt の型解決](https://detekt.dev/docs/gettingstarted/type-resolution/)

## 検証アプリ固有の検査

`reservation-contract` は、各 Port を呼び出せる Core を指定します。呼び出しや呼び出し可能参照をシンボルから解決する処理は、汎用ルールと共有します。別のプロダクトへ導入するときは、その業務の Port、操作、所有 Core を指定します。

| ルール | 検査する呼び出し | 呼び出せる場所 |
| --- | --- | --- |
| `EligibilityPortCall` | 資格の照会 | 参加資格 Core |
| `ReservationPortCall` | 予約の作成、照会、取消、現在状態の照会 | 予約 Core |
| `CheckInPortCall` | 受付の作成と照会 | 来場受付 Core |

[dca.yml](../product/config/detekt/dca.yml) の `decisionFunctions` と `decisionTypes` は、見直しが必要な判断箇所と型を指定します。たとえば、資格結果から予約に進めるかを決める処理と、資格の状態を画面へ変換する処理を登録します。`restrictedCallables` と `allowedCallerPackages` は、Core の組み立て関数と呼び出し元を指定します。

`./gradlew check` は、Android・JVM・KMP の型解決付きタスクを実行します。KMP の `detektMainJvm` と `detektMainAndroid` は `commonMain` も検査します。`:conformance` の診断には検査用の違反が含まれ、テストはルール名と診断行を照合します。許可した組み立てパッケージの所有モジュール、Gradle の依存宣言、Core と UI の import を同じ検査で確認します。特に基準仕様では、来場受付 Core から参加資格 Core への参照を禁止します。

## 型・ビルド・実行時の確認

| 確認したいこと | 方法 |
| --- | --- |
| モジュール外で確認済みの値や操作の前提を構築できないこと | コンストラクタと発行処理の可視性を絞り、禁止した利用コードのコンパイルが失敗することを確認する |
| 必須 Handler の実装や供給に漏れがないこと | 委譲がある場合に、抽象メソッドと必須引数を使う |
| UI が外部 Adapter に直接依存できないこと | Gradle のモジュール依存を確認する。公開された組み立て関数への参照は detekt でも調べる |
| 利用側が必要な結果の違いを取得できること | `Boolean` や `null` にまとめる箇所は、受け取った側の判断まで追ってレビューする |
| 期限の境界、照会対象、要求回数が正しいこと | 時刻や外部サービスの応答を制御したテストで確かめる |

[検証アプリの実行手順](../docs/android-product.md)にビルドと検査のコマンドを記載しています。規約の設計根拠は、隣接する `usapo-type-driven-design` の `10-coding-principles.md` です。
