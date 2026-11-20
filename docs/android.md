# Android と KMP への適用

予約できる条件と予約結果の意味は、業務 Core が決めます。Compose は利用者の操作を ViewModel に伝え、ViewModel は Core を呼び出します。ViewModel が結果を画面状態に変換し、Compose がその状態を描画します。この流れは Android の単方向データフローに沿います。[Android のアーキテクチャ推奨事項](https://developer.android.com/topic/architecture/recommendations)

## 責務と依存方向

図の矢印は、コードが静的に参照する向きです。Port は Core が外部に求める操作のインターフェースです。図は Port の宣言を Core に置く構成を示します。

```mermaid
flowchart LR
    Compose["Compose<br/>描画と操作通知"] --> ViewModel["ViewModel<br/>画面状態の生成"]
    ViewModel --> Core["業務 Core<br/>前提・結果・状態遷移・Port"]
    Adapter["Adapter<br/>通信と応答の解釈"] --> Core
    Composition["組み立て<br/>Core と実装の接続"] --> Core
    Composition --> Adapter
    Composition --> ViewModel
```

Core は結果の意味と、次に許される操作を公開します。ViewModel はその結果から画面状態を作り、Compose は文言やボタンを選びます。たとえば取消操作が渡された画面には、Compose が取消ボタンを表示します。利用者がボタンを押した時点の取消期限は Core が確認します。

Adapter は外部の応答を検証し、Port が定義する観測結果に変換します。予約送信後に応答を確認できず成立を判定できない場合、Core が結果不明として扱える観測を渡します。未定義の応答コードは Adapter で扱います。仕様で業務結果が追加された場合は、Core の契約にも新しい結果を加えます。通信や SDK に固有の型は Adapter 内に留めます。

組み立てを担当するコードが Adapter の実装を選び、Core の必須引数として渡します。UI は組み立て済みの Core の操作を受け取ります。UI からも公開 factory を参照できる構成では、静的検査で呼び出し元を制限します。Gradle では、モジュール間に禁止した依存が加わっていないかを検査します。Kotlin の `internal` が制限するのは、コンパイルモジュール外からの参照です。[Kotlin の可視性](https://kotlinlang.org/docs/visibility-modifiers.html)

## 応答が失われたとき

応答を受け取れなかった場合、ViewModel は結果不明を表す画面状態を作ります。図は、利用者が元の要求を照会して予約の成立を確認できた場合の呼び出しと応答を示します。

```mermaid
sequenceDiagram
    participant V as ViewModel
    participant C as 業務 Core
    participant A as Adapter
    participant S as 予約サービス
    V->>C: 確認済みの内容で送信
    C->>A: 予約作成を要求
    A->>S: 要求 ID と予約内容を送信
    A-->>C: 応答を受信できなかった
    C-->>V: 結果不明・元の要求を照会可能
    V->>C: 利用者の操作で照会
    C->>A: 元の要求 ID で照会
    A->>S: 予約結果を取得
    S-->>A: 元の予約の成立を確認
    A-->>C: 予約確定の観測
    C-->>V: 予約確定
```

## 再生成と操作の有効性

画面を再生成したときは、現在の業務状態から画面を描き直します。復帰や再購読のたびに要求を送ると、前回の要求が成立していた場合に予約が重複します。同時実行を扱う Core は一つの排他区間で操作が有効かを確かめ、状態を送信中へ移します。送信中に届いた重複操作は、外部要求を作る前に拒否します。通信後に成立を確認できなければ、元の要求を照会します。

プロセス終了後の復元を扱う場合は、画面を復元するための ID と、元の要求を再照会するための永続情報を、それぞれどこで管理するか決めます。Android の `SavedStateHandle` は画面を再構築する少量の情報に使えます。予約の成立や送信済みの事実は、サービスや永続記録で確認します。[Android の UI 状態保存](https://developer.android.com/topic/libraries/architecture/saving-states?hl=en)

## 検証用アプリでの状態管理

[Android の検証用アプリ](android-product.md)では、組み立て時にアプリのプロセスで共有する Core を作ります。Core が下書き、進行中の要求、確定済みの予約を管理します。画面が切り替わっても、元の要求を照会し、確定済みの予約を取り消せます。操作の有効性は Core が判断し、Jetpack Compose は状態に応じた操作を表示します。

[Kotlin/JVM のサンプル](../examples/README.md)では、確認済みの値や結果の分岐をコンパイラがどう検査するかを確認できます。Android の検証用アプリは、シミュレーターで再現した結果と実際の画面操作を使って確かめます。

責務と Port の設計根拠は `usapo-type-driven-design` の `08-module-structure.md` と `09-external-adapters.md` です。
