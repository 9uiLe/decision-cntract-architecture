# Decision Contract Architecture の設計

判断契約は、業務操作に必要な前提、その操作が返す結果、結果ごとに許される操作を定めます。定員制ワークショップを例に、判断を誰が担い、結果を受け取る側の対応漏れをどこで検出するかを説明します。

## 設計と基準仕様

- [設計思想と判断契約](architecture.md)
- [モバイルアプリへの適用](mobile-app/architecture.md)
- [3つの Core の判断契約と依存関係](mobile-app/core-contracts.md)
- [判断済みの値が使える時点](mobile-app/contract-evidence.md)
- [検証アプリの技術選定](mobile-app/technology.md)
- [参加資格](mobile-app/qualification.md)
- [予約の作成・照会・取消](mobile-app/screens.md#予約の作成照会取消)
- [来場受付](mobile-app/check-in.md)
- [画面と操作](mobile-app/screens.md)
- [Android・iOS の実行手順](mobile-app/setup.md)

## 仕様変更の検証

- [検証方法と課題 A〜G](verification.md)
- [H. 資格に一時停止を追加する](experiments/h-suspended-qualification.md)
- [I. 来場時に資格を再確認する](experiments/i-check-in-qualification.md)
- [J. 予約に利用停止を追加する](experiments/j-suspended-reservation.md)
- [K. 資格不要の新規予約を廃止する](experiments/k-required-qualification.md)
- [L. 資格サービスの形式を変更する](experiments/l-qualification-wire-format.md)
- [比較 C1〜C3. 判断契約の制約を弱める](experiments/contract-comparisons.md)
- [検証記録](mobile-app/verification-results.md)

設計の由来は `usapo-type-driven-design`、ワークショップ予約の題材と変更課題の由来は `dca-mobile-product-spec` です。上の文書に、このリポジトリのアプリを理解して実行するための仕様と確認手順を記載しています。
