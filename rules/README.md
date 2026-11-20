# 運用ルールと detekt の検査仕様

型と可視性で拒否できる経路は、Kotlin の契約で閉じます。Gradle はモジュール間の依存を検査します。detekt は、型が合っていても判断契約を迂回する書き方を、対象を絞って検査します。

## detekt で検査する候補

次のルールはプラグインの実装仕様です。初版は検出条件と設定項目を定義します。プラグインを組み込んだ時点で、違反例と許可例を detekt のテストに追加します。

| ルール ID 候補 | 設定する適用範囲 | 違反として報告する条件 | 許可例 |
| --- | --- | --- | --- |
| `ExhaustiveDecisionWhen` | `decisionFunctions` に必須判断を持つ関数、`decisionTypes` に閉じた業務型を完全修飾名で指定 | 指定した関数内で、`when` の対象式が指定型に解決され、`else` がある | 外部 DTO のコードを解釈する `else`、Kotlin が網羅性を検査する `when` |
| `RestrictedCompositionCall` | `restrictedCallables` に組み立て関数のシンボル、`allowedCallerPackages` に許可する呼び出し元のパッケージを指定 | 許可範囲外から対象シンボルを呼び出すか、呼び出し可能参照として取得する | 組み立て側からの呼び出し、別の同名関数 |

どちらもシンボル解決を必須にします。型エイリアスや import の別名を付けても、同じ型や関数へ解決されるなら検出対象です。解決できない解析結果で検査が有効と判断しないように、CI では型解決が有効なタスクを実行し、検出されるべき違反例をテストします。組み立て制限では許可するパッケージとその配下を区切り文字込みで照合し、接頭辞が偶然同じパッケージを除外します。オーバーロードを区別する必要がある場合は、引数型を含むシグネチャを設定します。

実装時は detekt のバージョンを固定し、そのバージョンの `RuleSetProvider` を ServiceLoader に登録します。消費側では `detektPlugins` にプラグインを追加し、設定で個々のルールを有効にします。ルール ID と設定項目ごとに、実際に設定を読み込んだ解析で違反例を検出し、許可例が通ることを確認します。[detekt の拡張ガイド](https://detekt.dev/docs/introduction/extensions/)

設定例はプラグイン実装時の YAML の形を示します。関数名、型名、パッケージ名は適用先のコードに合わせます。

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
| 時刻の境界や照会先の同一性が正しいこと | 実行テストで、時刻・要求 ID・新規送信回数を観測する |

規約の設計根拠は `usapo-type-driven-design` の `10-coding-principles.md` です。
