package example.reservation.ui

import androidx.compose.ui.Modifier

// 画面の識別子（testTag）を OS の自動操作へ公開する方法は OS ごとに異なる。
expect fun Modifier.exposeTestTags(): Modifier
