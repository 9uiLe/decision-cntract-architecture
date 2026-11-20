package example.app.composition

// プロセスの寿命の間に共有する Core は一つだけ作る。画面や OS の入口が増えても、予約の状態を分けない。
object AppContainers {
    val shared: AppContainer by lazy { AppContainer() }
}
