# Kotlin/JVM サンプルのセットアップ

定員制ワークショップ予約を題材に、結果の違いが次の操作にどう反映されるかを JVM で確かめます。

開発シェルの対象は Apple Silicon の macOS、および arm64 と x86-64 の Linux です。

1. `nix --version` を実行してください。Nix が入っていない場合は、OS に対応する公式手順で導入してください。[Nix のインストール](https://nix.dev/install-nix.html)
2. リポジトリのルートで開発シェルを起動してください。Nix は [flake.lock](../flake.lock) で固定した依存から Kotlin と JDK を用意します。シェル内で両方のバージョンを確認してください。

   ```sh
   nix --extra-experimental-features 'nix-command flakes' develop
   kotlinc -version
   java -version
   ```

`flake.nix` をまだ Git に追加していない場合は、`develop` の引数に `path:.` を指定してください。[Nix の Flake の扱い](https://nix.dev/concepts/flakes.html)

## 予約の判断契約を検証する

開発シェル内で、リポジトリのルートから次のコマンドを実行してください。

```sh
bash examples/reservation/verify.sh
```

スクリプトは Core と利用側を別々にコンパイルし、開始時刻の境界、照会先の要求 ID、外部要求の回数をテストします。テストに通ると `ok:` を表示します。続いて、確認前の値で送信する例や、結果不明を再試行へ渡す例をコンパイルします。対象の行で期待した診断が出ると `拒否を確認` と表示します。

最後に、スクリプトは一時ディレクトリにコピーした `Outcome` に承認待ちを追加します。Core のコンパイルが成功し、結果を画面状態に変換する `project` に `NO_ELSE_IN_WHEN` が出ることを確認します。[実験 A](../docs/verification.md#予約アプリで評価する変更)で扱う変更のうち、画面状態への変換に対応漏れがある場合の検証です。

## コードを読む

| 見る契約 | ファイル |
| --- | --- |
| 確認済みの内容を表す型と、照会・再試行に必要な型 | [Model.kt](reservation/core/Model.kt) |
| 送信と照会でサービスから観測できる内容 | [Port.kt](reservation/core/Port.kt) |
| 前提を発行し、操作時点で現在の状態と照合する処理 | [ReservationCore.kt](reservation/core/ReservationCore.kt) |
| 結果を画面状態に変換する網羅的な分岐 | [Projection.kt](reservation/ui/Projection.kt) |
| 正規の呼び出しと、拒否されることを確かめる呼び出し | [試験シナリオ](reservation/tests/Main.kt)、[コンパイル負例](reservation/reject/) |

サンプルは外部サービスをテスト用の実装に置き換え、開催回を選んだ後の一つの予約フローを Kotlin/JVM で同期的に実行します。再試行は同じフローの操作です。開催回や参加内容を選び直す場合は、別のフローを開始する想定です。実アプリで確認する項目は[予約プロダクトの検証計画](../docs/verification.md)を参照してください。対象には Android の画面とライフサイクル、同時操作や再入、取消、永続化が含まれます。
