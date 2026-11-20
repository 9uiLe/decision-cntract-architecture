# Kotlin/JVM サンプルのセットアップ

定員制ワークショップ予約を題材に、結果の違いが次の操作にどう反映されるかを JVM で確かめます。

開発シェルの対象は Apple Silicon の macOS、および arm64 と x86-64 の Linux です。

1. `nix --version` を実行する。Nix が入っていない場合は、OS に対応する公式手順で導入する。[Nix のインストール](https://nix.dev/install-nix.html)
2. このリポジトリのルートで次のコマンドを実行する。Nix が [flake.lock](../flake.lock) に固定された依存から Kotlin と JDK を用意する。

   ```sh
   nix --extra-experimental-features 'nix-command flakes' develop
   kotlinc -version
   java -version
   ```

Git に追加する前の `flake.nix` を使う作業ツリーでは、`develop` の引数に `path:.` を指定します。[Nix の Flake の扱い](https://nix.dev/concepts/flakes.html)

## 予約の判断契約を検証する

開発シェル内で、リポジトリのルートから実行します。

```sh
bash examples/reservation/verify.sh
```

検証は Core と利用側を別々にコンパイルし、正常な操作を実行します。確認前の値での送信や、結果不明を再試行へ渡す呼び出しについては、コンパイルが失敗し、期待した診断が出ることを確かめます。実行時の検証では、開始時刻の境界、照会対象の要求 ID、外部要求の回数を観測します。

最後に、一時ディレクトリの `Outcome` に承認待ちを追加します。Core のコンパイルを通したうえで、画面状態に変換する `project` が `NO_ELSE_IN_WHEN` で拒否されることを確認します。これは[実験 A](../docs/verification.md#予約アプリで評価する変更)のうち、公開結果を受け取る箇所への伝播を確かめる試験です。

## コードを読む

| 見る契約 | ファイル |
| --- | --- |
| 確認済みの内容、結果不明から使う照会、未成立確定から使う再試行 | [Model.kt](reservation/core/Model.kt) |
| 送信と照会でサービスから観測できる内容 | [Port.kt](reservation/core/Port.kt) |
| 前提を発行し、操作時点で現在の状態と照合する処理 | [ReservationCore.kt](reservation/core/ReservationCore.kt) |
| 結果を画面状態へ変換し、新しい結果への対応を要求する分岐 | [Projection.kt](reservation/ui/Projection.kt) |
| 正規の呼び出しと、拒否されることを確かめる呼び出し | [試験シナリオ](reservation/tests/Main.kt)、[コンパイル負例](reservation/reject/) |

サンプルは開催回を選んだ後の一つの予約フローを、同期の Kotlin/JVM とフェイクの外部サービスで表現します。再試行は同じフローの操作です。開催回や参加内容を選び直す場合は、別のフローを開始する想定です。Android の画面とライフサイクル、同時・再入操作、取消、永続化は[予約プロダクトの検証計画](../docs/verification.md)で扱います。
