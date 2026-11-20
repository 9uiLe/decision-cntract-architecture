# Decision Contract Architecture の設計

定員制ワークショップの予約を送信した後、応答が途切れたとします。サーバーでは予約が成立しているかもしれません。画面がすべての失敗を一つのエラーにまとめて「再試行」を出すと、同じ予約を二重に作る恐れがあります。一方、未成立が確定していれば、利用者は明示的に再試行できます。この違いを、結果から次の操作を決める場所まで保つのが設計の出発点です。

1. [設計思想と判断契約](architecture.md)で、結果を受け取る側にどの判断を求めるかを確認する。
2. [Android と KMP への適用](android.md)で、Core、Adapter、画面状態、組み立ての責務を確認する。
3. [仕様変更を使った検証](verification.md)で、コンパイルで検出したい変更と動作テストで確かめる変更を確認する。

設計の根拠は作業ディレクトリに隣接する `usapo-type-driven-design` の `01-design-intent.md` から `13-verification.md` です。予約の要件は `dca-mobile-product-spec` の `product/requirements/`、変更課題は同じ資料の `experiments/` にあります。このリポジトリの読解とサンプルの実行には、隣接ディレクトリのチェックアウトは不要です。
