# 検証記録

コミット `30f5e84` の[基準仕様](core-contracts.md)と、そこから個別に実行した変更実験・比較実験の結果です。

## 基準仕様

| 検証 | 確認したこと |
| --- | --- |
| コンパイル契約 | 独立した JVM コンパイルで正規の利用例1件に成功。証拠や操作券の偽造、成立記録と現在の観測の混同を含む負例15件は、ファイル・行・診断 ID が一致 |
| 依存関係・detekt | 汎用ルールとプロダクト固有ルールのテスト49件。Android で実行する conformance 9件で、意図した違反23件と禁止モジュール参照を確認 |
| Core・シミュレーター・画面状態 | JVM と iOS Simulator 向け Kotlin/Native で各102件。参加資格7、予約46、来場受付16、シミュレーター27、画面状態6 |
| 同じ型で起きる誤判断 | コピーで取消期限の `>=` を `>` に変えると、予約テスト46件のうち期限ちょうどの1件が失敗。観測時刻の区間照合を外すと、古い資格確認1件と古い予約確認2件の再利用を拒否するテストが失敗 |

資格確認は対象者と種別を照合し、予約を初回送信・再試行する直前に全員分の期限を再確認します。再確認で把握した最新の資格状態を画面へ反映します。確認中の別の操作が更新した状態は保持します。

受付では、別の予約にしか含まれない参加者と、予約が結果不明の参加者について作成要求が発生しないことを確認しました。開催72時間前に成立して48時間前に取り消した予約は、開催20分前の受付時間内に古い参加者情報を渡しても、受付要求と記録が増えません。取消の応答を失った場合も、サービスの現在状態を使います。予約時に2人とも資格があり、来場時には一方が失効している場合、基準仕様では2人とも受け付けられます。

[Kotlin/JVM サンプル](../../examples/reservation-jvm/README.md)では、正例9件、誤用4件、課題 A の画面状態への変換で `NO_ELSE_IN_WHEN` を確認しました。F の結果は上の表にあります。[課題 A〜G の確認項目](../verification.md#予約アプリで評価する変更)

## OS ごとの操作

| 対象 | ビルドと操作 |
| --- | --- |
| Android | API 36 エミュレーター用 APK で2人の資格確認と予約、受付開始時刻での受付、背景化からの復帰、元の受付要求の照会を確認。システムの戻る操作で検証用画面から予約へ復帰 |
| iOS | Xcode 27.0 で iOS 27.0 Simulator 向けアプリをビルド。通常起動と起動引数による検証画面の切り替え、予約、受付、背景化からの復帰と照会を確認。Kotlin の iOS 実機ターゲットは共通フレームワークのリンクまで |

受付の応答だけが失われた設定で、照会後に佐藤さんだけが受付済みとなり、鈴木さんは未受付のままでした。両 OS とも予約1件、受付作成1回、受付照会1回、受付記録1件を観測しました。

## 変更実験 H〜L

各課題は同じ基準ソースから個別に実行しました。ソースの SHA-256 は `a3a8eaaae0c6ed0731e56abd8ecca4b87b240d7dff9cd37f34e3c195285ae1e0` です。Kotlin 2.3.21 の JVM 診断、対応後のテスト、差分の範囲を照合しました。

| 課題 | 未対応を検出した箇所 | 対応後と変更範囲 |
| --- | --- | --- |
| [H](../experiments/h-suspended-qualification.md) | 予約前の判断、資格の観測を解釈する2箇所、画面への変換と拒否理由の表示に `NO_ELSE_IN_WHEN` | 一時停止中の予約作成は0回。成立済み予約は受付可能。予約の送信処理と来場受付 Core を維持 |
| [I](../experiments/i-check-in-qualification.md) | 成立時の参加者情報だけを渡す `ReservationViewModel.kt:74:111` に `ARGUMENT_TYPE_MISMATCH`。拒否理由を表示する `Messages.kt:93:45` に `NO_ELSE_IN_WHEN` | 来場時に資格がある A だけを受付。別人・別種別、未取得、期限切れ、確認不能を区別。資格不要で成立した予約の受付、参加資格 Core の判定、予約 Core の成立・取消を維持 |
| [J](../experiments/j-suspended-reservation.md) | `ReservationCore.admission`、その修正後の `Messages.unavailableMessage` に `NO_ELSE_IN_WHEN` | 停止前の受付と予約履歴を保持し、停止後の新規受付は作成しない。期限内の取消は可能。来場受付と参加資格の Core を維持 |
| [K](../experiments/k-required-qualification.md) | `ReservationViewModel.confirm` に残した従来の資格不要の呼び出し2件に `UNRESOLVED_REFERENCE` | 通常回の新規予約に「基礎安全講習修了」を要求。旧予約の成立履歴と受付を維持。参加資格と来場受付の Core の実装を維持 |
| [L](../experiments/l-qualification-wire-format.md) | `QualificationWire.kt` と `Simulator.kt` に残した旧フィールドの名前付き引数に `NAMED_PARAMETER_NOT_FOUND` と `NO_VALUE_FOR_PARAMETER` | 旧形式と新形式の入力をそれぞれ同じ資格・有効期限に変換。版の取り違え、未知のコード、不正な期限は有効と判断しない。差分は Adapter とそのテスト内 |

## 比較 C1〜C3

| 比較 | 通常構成で観測する診断 | 制約を弱めた構成で観測する結果 |
| --- | --- | --- |
| C1 | 一時停止に未対応の `Projection.kt:68:71` で `NO_ELSE_IN_WHEN` | `else` を追加すると Kotlin のコンパイルに成功し、detekt は `Projection.kt:72:5` に `ExhaustiveDecisionWhen` を1件報告 |
| C2 | 発行元とは別のモジュールから証明書を直接作る `ForgeEligibilityCertificate.kt` で `INVISIBLE_REFERENCE` | コンストラクタを公開すると同じコードがコンパイル可能になり、その利用コードを拒否するテストだけが失敗 |
| C3 | 課題 I で、要資格予約の受付に証明書を渡さない `CheckInWithoutQualification.kt` で `ARGUMENT_TYPE_MISMATCH` | 引数1つの互換操作を追加すると同じコードがコンパイル可能になり、その利用コードを拒否するテストだけが失敗 |

比較の外側では、期待する失敗だけが起きたことを照合します。各比較の実行方法は [判断契約の制約を弱める比較](../experiments/contract-comparisons.md) にあります。

## 再現手順

現在のチェックアウトで同じ検査を実行する手順です。Android 用の Nix シェルを使ってください。iOS のビルドは Xcode とシミュレーターがある macOS で行います。Nix シェル内の `DEVELOPER_DIR` は実行手順に沿って設定してください。[実行手順](setup.md#ビルド時の検査を実行する)

```sh
cd examples/reservation-mobile
./gradlew check :app:assembleDebug \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64
```

H〜L の段階と期待する診断は、[変更実験の各 `steps.json`](../../examples/reservation-mobile/verification/experiments/) に記載しています。実行すると、診断、成功したテスト、変更範囲が指定した出力先に保存されます。[変更実験の実行手順](../verification.md#3つの-core-を使う変更実験) · [比較実験の実行手順](../experiments/contract-comparisons.md#実行)

端末での検証はエミュレーターとシミュレーターでの操作です。実機での操作と Compose の自動 UI テストは未実施です。外部サービスの観測はシミュレーターの記録に基づきます。
