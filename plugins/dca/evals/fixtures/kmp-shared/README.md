# 来場受付(評価用の fixture)

Kotlin Multiplatform の共通コードと、プラットフォーム固有のコードを持つ小さな構成です。

## 構成

- `shared/src/commonMain`: 受付の判定。Android と iOS で共有します。
- `shared/src/androidMain`: Android 固有の時刻の取得。
- `shared/src/iosMain`: iOS 固有の時刻の取得。

## 仕様メモ

- 受付できるのは、開催回の開始時刻より前までです。
- 時刻の取得は、プラットフォームごとに実装します。
