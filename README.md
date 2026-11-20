# Decision Contract Architecture

**仕様が変わったとき、見直すべき業務判断をコンパイラの診断からたどる。**

ワークショップの予約要求に応答がないとき、もう一度送ってよいでしょうか。最初の予約が成立していれば、再送は重複につながります。Decision Contract Architecture（DCA）は、業務操作の前提、結果、次に許される操作を Kotlin の型で表し、その型を利用側が直接参照する設計です。

[最小の JVM サンプルを実行する](examples/reservation-jvm/README.md) · [モバイル版を図で読む](examples/reservation-mobile/docs/README.md)

## 予約フローで見る DCA

Core が確認済みの予約内容を発行し、送信にはその値を要求します。送信後は結果に応じて、画面が利用者に案内できる操作が変わります。

| Core が返す結果 | 次に選べる操作 |
| --- | --- |
| 結果不明 | 元の要求 ID で状況を照会する |
| 未成立が確定した | 開催回の開始前なら、新しい要求として再試行する |

結果を `sealed` 型で表し、画面への変換を `else` のない `when` で書くと、結果を追加したときに対応漏れをコンパイラが検出します。Core は操作券を現在の状態と照合し、新しく予約要求を送る直前に期限を確認します。開始時刻ちょうどに作成要求を送らないことはテストで確かめられます。 [JVM 版の図解](examples/reservation-jvm/docs/README.md) · [設計思想と判断契約](docs/architecture.md)

## 仕様変更で何が見つかるか

Android・iOS で画面を共有する検証アプリは、参加資格、予約、来場受付を3つの Core に分けています。参加資格に「一時停止」を加えると、予約前の判断や画面表示で対応が必要な箇所にコンパイル診断が出ます。対応後のテストでは、一時停止中に資格が必要な予約の作成要求が送られず、成立済みの予約で来場を受け付けられることを確かめます。 [変更実験 H](docs/experiments/h-suspended-qualification.md) · [診断とテストの記録](docs/mobile-app/verification-results.md#変更実験-hl)

比較実験では、未対応の `when` に `else` を加えるとコンパイルが通り、detekt がその `else` を報告します。 [比較実験 C1](docs/experiments/contract-comparisons.md) · [detekt のルール](docs/detekt-rules.md)

## 自分のプロジェクトで試す

次の操作が結果によって変わる機能を一つ選んでください。結果ごとに許される操作を書き出し、利用側が結果の違いを型で受け取れる形にします。JVM サンプルを実行すると、予約結果にケースを一つ加えたとき、未対応の画面変換に診断が出ることを確認できます。 [JVM 版の実行手順](examples/reservation-jvm/README.md)

[Android・iOS 版の実行手順](docs/mobile-app/setup.md) · [サンプル一覧](examples/README.md) · [ドキュメント索引](docs/README.md)
