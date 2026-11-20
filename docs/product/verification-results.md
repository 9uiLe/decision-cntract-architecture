# 検証記録

[基準仕様](core-contracts.md)は、参加資格、予約、来場受付の3つの Core を使います。課題 H〜L は基準仕様のコピーに個別に適用します。通常のアプリは基準仕様で動作します。

## 基準仕様

| 検証 | 確認したこと |
| --- | --- |
| コンパイル契約 | 独立した JVM コンパイルで正規の利用例1件に成功。証拠や操作券の偽造、未確認の入力、予約と受付の取り違えなどの負例12件は、ファイル・行・期待した診断が一致 |
| 依存関係・detekt | 汎用ルールとプロダクト固有ルールのテスト33件。conformance の8件で、意図した違反19件と禁止モジュール参照を確認。検証用の違反を識別できるように検査の出力に診断を残す |
| Core・シミュレーター・画面状態 | JVM と iOS Simulator 向け Kotlin/Native で各90件。参加資格6、予約40、来場受付13、シミュレーター25、画面状態6 |
| 取消期限の負の対照（課題 F） | コピーで取消期限の `>=` を `>` に変更。コンパイルは通り、予約テスト40件のうち、取消期限ちょうどの1件が失敗 |

資格確認は対象者と種別を照合し、予約を初回送信・再試行する直前に全員分の期限を再確認します。再確認で把握した最新の資格状態を画面へ反映します。確認中の別の操作が更新した状態は保持します。

受付では、別の予約にしか含まれない参加者と、予約が結果不明の参加者について作成要求が発生しないことを確認しました。取消済みの予約も同じです。取消は成立して応答だけを失った場合も、サービスの現在状態で判定します。予約時に2人とも資格があり、来場時には一方が失効している場合、基準仕様では2人とも受け付けられます。

初版からの [課題 A〜G](../verification.md#予約アプリで評価する変更) のうち、独立した Kotlin/JVM サンプルで A の投影の診断を再確認しました。正例9件、誤用4件、結果を追加した場合の `NO_ELSE_IN_WHEN` です。B〜E と G はリンク先に受入条件を記載しています。G はプロセス終了をまたぐ復元を追加する独立課題です。

## OS ごとの操作

| 対象 | ビルドと操作 |
| --- | --- |
| Android | API 36 エミュレーター用 APK で2人の資格確認と予約、受付開始時刻での受付、背景化からの復帰、元の受付要求の照会を確認。システムの戻る操作で検証用画面から予約へ復帰 |
| iOS | Xcode 27.0 で iOS 27.0 Simulator 向けアプリをビルド。通常起動と起動引数による検証画面の切り替え、予約、受付、背景化からの復帰と照会を確認。Kotlin の iOS 実機ターゲットは共通フレームワークのリンクまで |

受付の応答だけが失われた設定で、照会後に佐藤さんだけが受付済みとなり、鈴木さんは未受付のままでした。両 OS とも予約1件、受付作成1回、受付照会1回、受付記録1件を観測しました。

## 変更実験 H〜L

同じ基準のソース（SHA-256 `75db775bc6ac060086ef420b3f568eb6bf74e514648372d2b8a629385181e53a`）から、各課題で期待した JVM 診断、対応後のテスト、変更範囲を確認しました。

| 課題 | 未対応を検出した箇所 | 対応後と変更範囲 |
| --- | --- | --- |
| [H](../experiments/h-suspended-qualification.md) | `ReservationCore.readinessOfOutcome` と、上流を修正した後の `Projection.projectOutcome` に `NO_ELSE_IN_WHEN` | 一時停止中の予約作成は0回。成立済み予約は受付可能。予約の送信処理と来場受付 Core を維持 |
| [I](../experiments/i-check-in-qualification.md) | 予約の確認だけを渡す `ReservationViewModel` に `ARGUMENT_TYPE_MISMATCH`。拒否理由を表示する `Messages.kt` に `NO_ELSE_IN_WHEN` | 来場時に資格があるAだけを受付。別人と別種別の証拠は拒否。資格不要で成立した予約の受付、参加資格 Core の判定、予約 Core の成立・取消を維持 |
| [J](../experiments/j-suspended-reservation.md) | `ReservationCore.admission`、その修正後の `Messages.unavailableMessage` に `NO_ELSE_IN_WHEN` | 停止前の受付と予約履歴を保持し、停止後の新規受付は作成しない。期限内の取消は可能。来場受付と参加資格の Core を維持 |
| [K](../experiments/k-required-qualification.md) | `ReservationViewModel.confirm` に残した従来の資格不要の呼び出し2件に `UNRESOLVED_REFERENCE` | 通常回の新規予約に「基礎安全講習修了」を要求。旧予約の成立履歴と受付を維持。参加資格と来場受付の Core の実装を維持 |
| [L](../experiments/l-qualification-wire-format.md) | `QualificationWire.kt` と `Simulator.kt` に残した旧フィールドの名前付き引数に `NAMED_PARAMETER_NOT_FOUND` と `NO_VALUE_FOR_PARAMETER` | 旧形式と新形式の入力をそれぞれ同じ資格・有効期限に変換。版の取り違え、未知のコード、不正な期限は有効と判断しない。差分は Adapter とそのテスト内 |

## 再実行と証拠

Android 用の Nix シェルで基準仕様を検査します。iOS のビルドは Xcode とシミュレーターがある macOS で行います。[実行手順](../android-product.md)

```sh
cd product
./gradlew check :app:assembleDebug \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkDebugFrameworkIosArm64
```

[変更実験の段階と期待診断](../../product/experiments/run.py) は `product/experiments/{h,i,j,k,l}/steps.json` に記録しています。リポジトリのルートから次のコマンドを実行すると、診断、成功したテスト、許容した変更範囲を再確認できます。

```sh
python3 product/experiments/run.py all --evidence runs/multicore/experiments
```

今回のローカル実行結果は `runs/multicore/` にあります。基準版の検査は `final-baseline-check.log`、変更実験の診断とテストは `final-experiments/`、両 OS の画面と要求回数は `final-ui/` に記録しました。構成図は [Core の判断契約](core-contracts.md) にあります。

端末での検証はエミュレーターとシミュレーターでの操作です。実機での操作と Compose の自動 UI テストは未実施です。外部サービスの観測はシミュレーターの記録に基づきます。
