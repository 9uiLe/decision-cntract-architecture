# 同梱思想の索引

dca に同梱する DCA の思想と、各 Skill が共通で読む資料の所在を示す。作業に必要な行だけを読む。全資料を通して読まない。

## 共通資料

各 Skill が直接読む。ある Skill が別の Skill の実行結果を前提にせず、必要な共通資料を自分で読む。

| ファイル | 扱うこと | 主に読む Skill |
| --- | --- | --- |
| [common/information-model.md](common/information-model.md) | 情報の分類、調査と合意の単位、指摘の種類、結果の形式 | map、scan、interview、refactor |
| [common/record-storage.md](common/record-storage.md) | 外部記録の保存、識別、ソースの識別、再開 | map、scan、interview、refactor、guide |
| [common/migration-workflow.md](common/migration-workflow.md) | 段階移行、作業の種類の分離、新旧の併存 | refactor、interview |
| [common/verification.md](common/verification.md) | 保証ごとの検証、結果の記録、完了条件 | refactor、lint、scan |

## 同梱思想

次のファイルは、DCA の設計文書から複写して同梱する。複写は元の文書のままである。画像と、元のリポジトリのファイルへの相対リンクは、同梱先では解決しない。

| トピック | 同梱するファイル | 出典 | 複写元 |
| --- | --- | --- | --- |
| 判断契約、前提と有効期限、Handler、操作を持つ画面入力の更新 | dca/architecture.md | S01 | docs/architecture.md |
| 保証ごとの検証、仕様変更を使った評価 | dca/verification.md | S02 | docs/verification.md |
| 運用ルールと detekt の検査仕様 | dca/detekt-rules.md | S03 | docs/detekt-rules.md |
| 契約の制約を弱めた比較 | dca/experiments/contract-comparisons.md | S04 | docs/experiments/contract-comparisons.md |
| 業務仕様の変更で正当な依存が変わる例 | dca/experiments/i-check-in-qualification.md | S05 | docs/experiments/i-check-in-qualification.md |
| 値が表す事実、対象、観測の時点、鮮度の限界 | dca/mobile-app/contract-evidence.md | S06 | docs/mobile-app/contract-evidence.md |

複写したファイルと複写元の内容 hash の対応は、[dca/SOURCES.json](dca/SOURCES.json) に記録する。元の文書を変えたときは、複写と対応表を同じ変更で更新する。

## 必須の原則と任意の技法

出典は S01 の「必須の原則と任意の技法」と「適用範囲」である。違反として指摘できるのは、必須の原則と、利用者が合意した制約だけである。

| 区分 | 内容 |
| --- | --- |
| 必須 | 判断の所有者を定める |
| 必須 | 公開する値の意味と、発行できる箇所を定める |
| 必須 | 仕様変更のときに再判断する利用側を定める |
| 必須 | 型検査が働くと説明するときは、通常の利用側と同じコンパイル境界で、正例と負例を確かめる |
| 必須 | 対象や時刻が一致するか、外部操作が重複しないかは、状態と操作を所有する Core が実行時に判断する |
| 更新境界の制約 | 操作を持つ入力の更新を省く場合に、表示と操作の差を保つ。古いイベントは保持していた券を Core へ渡す |
| 任意 | 実際に判断を委ねるときに Handler を使う |
| 任意 | 独立した判断だけを小さな純粋関数に分ける |
| 任意 | 宣言から決まる接続が増えたときに、コード生成を選ぶ。生成した接続に、業務判断を補う既定の動作を持たせない |
| 発展 | Session Types や依存型は、より多くの前提を型で表す発展的な選択肢である |

適用範囲は S01 に従う。失敗時の扱いが一種類で、呼び出し側に再判断も求めない操作は、通常の型と局所的な分岐で足りる。型を増やすかどうかは、拒否したい操作と、仕様変更のときに判断を見直す必要がある利用側を基準に決める。

## 導入先への適用で守ること

- サンプルの Core の数、名前、依存関係、操作の許否を、導入先にそのまま当てはめない。
- 型で扱える契約と、現在の対象、時刻、状態を照合する実行時の条件は別のものとして扱う。確認済みの型を導入しても、鮮度や一度だけの利用が自動で保証されるとは扱わない。[S06]
- 思想に書かれていない判断を、その思想が必須としているように説明しない。dca が加える運用上の手順は、思想の根拠と区別して書く。
- 思想の意味が変わったときは、同梱思想、各 Skill の手順、例、Lint の提案、評価ケースの更新対象を示す。更新の義務の全文は、Plugin を管理するリポジトリの CLAUDE.md にある。
