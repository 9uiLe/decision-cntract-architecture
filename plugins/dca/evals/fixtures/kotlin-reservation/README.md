# ワークショップ予約(評価用の fixture)

既存の Kotlin/JVM 製品を模した、小さな予約フローです。DCA を導入する前の状態を表します。

## 仕様メモ

- 予約の作成要求に応答がないとき、予約が成立したかどうかは分かりません。
- 応答がないときは、元の要求の状況を照会します。新しい予約要求は送りません。
- 予約できなかったときは、画面に「予約できませんでした」と表示します。

## 構成

- `src/main/kotlin/example/reservation/ReservationService.kt`: 予約の送信と結果の判定
- `src/main/kotlin/example/reservation/ReservationScreen.kt`: 結果に応じた表示
- `src/test/kotlin/example/reservation/ReservationServiceTest.kt`: 既存のテスト
- `detekt.yml`: 既存の静的解析の設定
