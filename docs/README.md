# Decision Contract Architecture の設計

判断契約は、業務操作に必要な前提、その操作が返す結果、結果ごとに許される操作を定めます。ワークショップ予約を例に、業務上の判断を誰が担い、仕様変更への対応漏れをどこで検出するかを説明します。

1. [設計思想と判断契約](architecture.md)で、結果を受け取る側にどの判断を求めるかを確認してください。
2. [Android と KMP への適用](android.md)で、Core、Adapter、画面状態、組み立ての責務を確認してください。
3. [仕様変更を使った検証](verification.md)で、コンパイルと動作テストで何を確かめるかを確認してください。

予約画面を操作するには、[Android の検証用アプリの実行手順](android-product.md)を参照してください。

このリポジトリだけで資料を読み、サンプルを実行できます。設計の出典は隣接する `usapo-type-driven-design` の `01-design-intent.md` から `13-verification.md` です。予約の要件と変更課題は、隣接する `dca-mobile-product-spec` の `product/requirements/` と `experiments/` にあります。
