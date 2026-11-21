# リポジトリの作業指示

## コードコメント

コードコメントは原則として書きません。処理の流れや意図は、名前・型・コードの構造で表します。期待する振る舞いはテストに記述します。

コメントを残すのは、その箇所のコードだけでは分かりにくいアーキテクチャ上の理由を伝える場合に限ります。たとえば、ある層に判断を置く理由、依存の向きを制限する理由、変更時に守るべき不変条件を、関係するコードの近くに日本語で簡潔に記述します。

アーキテクチャ全体に関わる方針は [設計文書](docs/README.md) に記載します。

## DCA 文書と dca Plugin の同期

DCA の思想、用語、判断基準、構成、例を変更するときは、同じ変更セットで `plugins/dca/` への影響を必ず確認し、関連する資材を更新します。

確認対象には、少なくとも次を含めます。

- `skills/principles/references/` に同梱する思想と、出典および内容 hash の対応（`dca/SOURCES.json`）
- 各 `skills/<name>/SKILL.md` の責務、入力、手順、出力、判断の境界
- 導入、調査、合意、段階移行、検証の例
- Lint が示す指摘、改善候補、その根拠
- `evals/` の評価ケースと期待結果
- `guide` の案内と、`overview` が示す Skill 間の関係
- host metadata、manifest、配布手順への影響

影響がある資材は、同じ変更セットで更新します。変更不要と判断した項目は、確認した対象と理由を変更の説明に残します。思想の変更と Plugin の追従を分離したまま、変更完了としません。

同梱思想は、`docs/` の元の文書を `python3 plugins/dca/scripts/dca_package.py sync-references` で複写し、`python3 plugins/dca/scripts/dca_package.py check` で追従を確認します。

Plugin に変更があるときは、`overview` Skill の手順で Plugin 自体の概要図を再生成し、`plugins/dca/docs/overview.svg` を表示して差分を確認します。再生成は `python3 plugins/dca/scripts/dca_overview.py extract` と `render` で行い、`check` で入力の hash、登録集合、参照の存在、再描画の一致を確認します。表示の確認では、Skill 登録との一致、関係を表す線の意味、参照根拠、日本語ラベルの読みやすさ、見切れと重なりを確かめます。再生成による差分がない場合も、そのことを確認結果に残します。

概要図は、実際の登録構成と Skill 定義から生成します。導入先のコードは入力にしません。生成した SVG、preview、過去の view データを、思想や構成の元として扱いません。入力の hash、登録集合、参照の存在、描画結果の一致は機械的に検査し、関係の意味が正しいことと表示の品質はレビューで確認します。通常の利用者は、パッケージに含まれる図を閲覧するだけです。再生成は、元リポジトリで保守するときに限ります。

Plugin 自体の `plugins/dca/docs/overview.svg` は、このリポジトリで管理します。導入先の調査、合意、進捗、検証の記録は、リポジトリ外のローカルなプロジェクトとフロー単位の記録として維持し、Plugin や導入先のコードリポジトリへ混入させません。

Plugin 名は `dca` とし、名称、版、Skill の登録を host 間で整合させます。host ごとの差分を追加しても、共通の判断基準や必須の検証要件を失わせません。未実施、失敗、実行不能の確認を、成功した確認として記録しません。

Plugin の変更を確認するときは、リポジトリのルートで `nix develop .#dca-plugin` を実行し、`plugins/dca` で `python3 -m unittest discover -s tests -t tests` を実行します。CI（`.github/workflows/dca-plugin.yml`）も同じ devShell を使います。
