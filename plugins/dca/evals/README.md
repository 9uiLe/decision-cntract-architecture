# dca の評価

dca が意図した場面で働き、過剰に適用せず、検証の欠落を成功と報告しないことを確かめるための評価ケースです。

## ケースの種類

`cases/` に 18 件のケース（E01 から E18）があります。各ケースは JSON で、次の項目を持ちます。

| 項目 | 内容 |
| --- | --- |
| `id`、`viewpoint` | ケースの番号と観点 |
| `skills`、`hosts` | 対象の Skill と host |
| `automation` | `deterministic` は標準のテストで判定します。`host-run` は host で実行し、出力を評価します |
| `fixture` | `fixtures/` の下の入力。使わないときは `null` |
| `setup`、`prompt` | 実行の前に整える状態と、host に渡す依頼 |
| `expected`、`must_not` | 期待する結果と、してはいけないこと |
| `evidence` | 判定に使う証拠 |
| `checks` | 決定的に確かめるテスト。`<テストファイル>.<クラス>.<テスト名>` で書きます |

`fixtures/` には、既存の Kotlin/JVM の予約フロー（`kotlin-reservation`）、Kotlin Multiplatform の構成（`kmp-shared`）、DCA が不要な小さな操作（`no-dca-needed`）があります。

## 決定的に確かめる

リポジトリのルートで `nix develop .#dca-plugin` を実行し、Python と Git が使えるシェルを開きます。そのシェルで次のコマンドを実行します。検査はモデルを呼び出さずに実行できます。

```bash
cd plugins/dca
python3 scripts/dca_package.py check
python3 scripts/dca_overview.py check
python3 -m unittest discover -s tests -t tests
```

`dca_package.py check` は、名称と版の一致、8 Skill の登録、起動方針の一致、リンク、同梱思想の追従、評価ケースの網羅、構成図の検査をまとめて行います。`deterministic` のケースは、`checks` のテストが存在することも確認します。

## host で実行する

`host-run` のケースは、対象の host と版、配布の経路を固定して実行します。

1. `setup` に従って、fixture を一時ディレクトリへ複写し、`git init` と commit を行います。外部記録の保存先は `DCA_STATE_DIR` で一時ディレクトリに指定します。
2. host で Plugin を有効にします。Claude Code は `/dca:<Skill 名>`、Codex CLI は `$` か `/skills` から `dca:<Skill 名>` を選んで呼び出します。
3. `prompt` を渡し、出力と、実行したコマンド、保存先を記録します。
4. `expected` と `must_not` に照らして判定します。

`cases/` の JSON は dca 独自の形式です。`claude plugin eval` が読む形式（`case.yaml`、または `prompt.md` と `graders/*.md`）ではなく、Codex CLI の評価コマンドが読む形式でもありません。このため、`claude plugin eval` で直接は実行できません。自動実行に使うには、ケースを `claude plugin eval` の形式へ変換して grader を設計する作業が別に要ります。この版では、その変換も実行もしていません。`claude plugin eval` は子の Claude を自分の認証で実行するので、費用と権限が発生します。使うときは利用者が実行を決めます。

## 結果の記録

結果は、ケースごとに JSON で記録します。評価したものと、観察したことと、判定を、記録だけで追えるようにします。

```json
{
  "case": "E01",
  "plugin_version": "0.1.0",
  "revision": "評価した対象リポジトリの commit、または作業ツリーの内容の識別",
  "host": {"name": "claude-code", "version": "2.1.330"},
  "distribution": "ローカルの marketplace",
  "conditions": ["必要な権限と実行条件", "DCA_STATE_DIR は一時ディレクトリ"],
  "fixture": "kotlin-reservation",
  "expected": ["ケースの expected と同じ内容"],
  "observed": ["観察した出力、操作、保存先"],
  "evidence": ["応答の本文の保存先", "実行したコマンドの一覧"],
  "satisfied": ["満たした条件"],
  "remaining": ["残る条件、確認できていない範囲"],
  "status": "成功",
  "reason": ""
}
```

| 項目 | 内容 |
| --- | --- |
| `case`、`plugin_version`、`revision` | ケースの番号、Plugin の版、評価した対象の revision |
| `host`、`distribution`、`conditions` | host の名前と版、配布の経路、必要な権限と実行条件 |
| `fixture`、`expected` | 入力の fixture と、ケースで定めた期待。ケースの定義と一致させます |
| `observed`、`evidence` | 観察した出力、操作、保存先と、判定に使った証拠 |
| `satisfied`、`remaining` | 満たした条件と、残る条件 |
| `status`、`reason` | 結果の区分と、失敗、実行不能、未実施の理由 |

`status` は、成功、失敗、実行不能、未実施のどれかです。失敗、実行不能、未実施には `reason` が要ります。成功には、`conditions`、`observed`、`evidence`、`satisfied` が要ります。実行不能のケースと、確認していない host を、成功の件数に含めません。問題を直したときは、直した問題に対応するケースを再実行し、前の結果との関係を残します。

`python3 scripts/dca_package.py check-result <結果の JSON>` で、必要な項目がそろっていることと、`fixture` と `expected` がケースの定義と一致することを確かめられます。判定の内容が正しいかどうかは、記録を読んで確認します。

## 初期リリースの評価の範囲

評価の結果には、対象の host と版、配布の経路を併記します。対象外の host や配布の経路は、対応済みと書きません。モデルを呼ぶ評価を実行していないケースは、未実施として残します。
