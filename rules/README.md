# 運用ルールと detekt の検査仕様

Kotlin の型と可視性で、許可する呼び出しや生成方法を制限します。Gradle ではモジュール間の依存を検査します。detekt のルールは型検査だけでは見つからない書き方のうち、構文と参照先から判定できるものを対象にします。

## detekt で検査する候補

次の表は detekt プラグインの実装案です。現在のリポジトリには検出条件と設定項目を記載しています。プラグインを組み込むときに、違反例と許可例を detekt のテストに追加します。

| ルール ID 候補 | 設定する適用範囲 | 違反として報告する条件 | 許可例 |
| --- | --- | --- | --- |
| `ExhaustiveDecisionWhen` | `decisionFunctions` に必須判断を持つ関数、`decisionTypes` に閉じた業務型を完全修飾名で指定 | 指定した関数内で、`when` の対象式が指定型に解決され、`else` がある | 外部 DTO のコードを解釈する `else`、Kotlin が網羅性を検査する `when` |
| `RestrictedCompositionCall` | `restrictedCallables` に組み立て関数のシンボル、`allowedCallerPackages` に許可する呼び出し元のパッケージを指定 | 許可範囲外から対象シンボルを呼び出すか、呼び出し可能参照として取得する | 組み立て側からの呼び出し、別の同名関数 |

両ルールで、解析時に型や関数の参照先を特定するシンボル解決を必須とします。型エイリアスや import の別名を使っていても、参照先が指定の型や関数と同じなら検出します。CI ではシンボル解決を有効にしたタスクを使い、違反例を検出できることを確認します。組み立て関数の呼び出しを制限するときは、許可したパッケージとその配下を対象にします。たとえば `example.app.composition` の許可は、名前の先頭が同じ `example.app.compositionExtra` には及びません。オーバーロードを区別する必要がある場合は、引数型を含むシグネチャを設定します。

実装時は detekt のバージョンを固定し、そのバージョンの `RuleSetProvider` を ServiceLoader に登録します。プラグインを使うプロジェクトでは、`detektPlugins` に追加し、設定で各ルールを有効にします。検証では実際に設定を読み込ませ、ルールごとに違反例を検出し、許可例を受け入れることを確認します。[detekt の拡張ガイド](https://detekt.dev/docs/introduction/extensions/)

次の YAML は実装案に対応する設定例です。関数名、型名、パッケージ名は適用先のコードに合わせます。

```yaml
decision-contract:
  active: true
  ExhaustiveDecisionWhen:
    active: true
    decisionFunctions: ['dca.ui.project']
    decisionTypes: ['dca.core.Outcome']
  RestrictedCompositionCall:
    active: true
    restrictedCallables: ['example.reservation.core.createReservationCore']
    allowedCallerPackages: ['example.app.composition']
```

## 型・ビルド・レビューで守ること

| 原則 | 確認する場所 |
| --- | --- |
| 確認済みの値を任意に発行・改変できると、操作の前提が崩れる | コンストラクタ、発行処理、`copy` の可視性。外部利用側のコンパイル失敗で確認する |
| 実装の不足を検出するため、必須 Handler のメソッドを抽象にし、組み立て引数を必須にする | Core の公開契約と組み立て箇所。正当な任意引数は許容する |
| UI が Core の正規入口を迂回して Adapter を使うと、業務判断が分散する | Gradle の依存許可表と、公開 factory に対する呼び出し制限 |
| 結果を `Boolean` や `null` にまとめると、後続で必要な意味が失われる場合がある | レビューで利用側の判断まで追う。表示用の要約や、本当に値が存在しない場合もあるため、型名だけで違反と判定しない |
| 開始時刻ちょうどに送信を拒否し、照会には元の要求 ID を使う | 実行テストで、判定時刻・要求 ID・新規送信回数を確認する |

規約の設計根拠は `usapo-type-driven-design` の `10-coding-principles.md` です。
