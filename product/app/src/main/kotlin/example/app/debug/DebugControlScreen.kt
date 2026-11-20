package example.app.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import example.adapter.simulator.CancelBehavior
import example.adapter.simulator.CreateBehavior
import example.adapter.simulator.LookupBehavior
import example.adapter.simulator.SimulatorControls
import example.app.composition.ControllableClock
import example.reservation.core.Catalog
import example.reservation.core.EpochMillis
import example.reservation.ui.formatPrecise

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun DebugControlScreen(controls: SimulatorControls, clock: ControllableClock, catalog: Catalog) {
    val observations by controls.observations.collectAsStateWithLifecycle()
    val now by clock.time.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Scaffold(topBar = { TopAppBar(title = { Text("検証用の操作") }) }) { padding ->
            Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section("観測") {
                    Text("現在時刻 ${formatPrecise(now)}", modifier = Modifier.testTag("observe-now"))
                    Text(
                        "作成要求 ${observations.createRequests} 回 ・ 照会 ${observations.lookupRequests} 回 ・ 取消 ${observations.cancelRequests} 回",
                        modifier = Modifier.testTag("observe-requests"),
                    )
                    Text("成立している予約 ${observations.activeReservations} 件", modifier = Modifier.testTag("observe-reservations"))
                    Text(
                        "保留中の呼び出し ${observations.heldCalls} 件 ・ 台本の待ち 作成 ${observations.queuedCreate} / 照会 ${observations.queuedLookup} / 取消 ${observations.queuedCancel}",
                        modifier = Modifier.testTag("observe-held"),
                    )
                }
                Section("次の予約作成の応答") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("通常成立", "create-succeed") { controls.enqueueCreate(CreateBehavior.Succeed) }
                        Choice("満席", "create-sold-out") { controls.enqueueCreate(CreateBehavior.SoldOut) }
                        Choice("作成前に拒否", "create-reject") { controls.enqueueCreate(CreateBehavior.RejectBeforeCreate) }
                        Choice("成立して応答喪失", "create-lose") { controls.enqueueCreate(CreateBehavior.LoseResponseAfterCreate) }
                        Choice("処理中のまま保留", "create-hold") { controls.enqueueCreate(CreateBehavior.HoldResponse) }
                    }
                }
                Section("次の結果照会の応答") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("記録どおり", "lookup-normal") { controls.enqueueLookup(LookupBehavior.Normal) }
                        Choice("記録が見つからない", "lookup-not-found") { controls.enqueueLookup(LookupBehavior.NotFound) }
                        Choice("照会に失敗", "lookup-fail") { controls.enqueueLookup(LookupBehavior.Fail) }
                        Choice("保留", "lookup-hold") { controls.enqueueLookup(LookupBehavior.Hold) }
                    }
                }
                Section("次の取消の応答") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("通常", "cancel-normal") { controls.enqueueCancel(CancelBehavior.Normal) }
                        Choice("取消を拒否", "cancel-decline") { controls.enqueueCancel(CancelBehavior.Decline) }
                        Choice("成立して応答喪失", "cancel-lose") { controls.enqueueCancel(CancelBehavior.Lose) }
                        Choice("保留", "cancel-hold") { controls.enqueueCancel(CancelBehavior.Hold) }
                    }
                }
                Section("保留と初期化") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = controls::releaseHeld, modifier = Modifier.testTag("release-held")) { Text("保留を解放") }
                        OutlinedButton(onClick = controls::reset, modifier = Modifier.testTag("reset-simulator")) {
                            Text("シミュレーターを初期化")
                        }
                    }
                }
                Section("時刻") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("1時間進める", "time-plus-hour") { clock.advance(HOUR) }
                        Choice("1時間戻す", "time-minus-hour") { clock.advance(-HOUR) }
                        Choice("1日進める", "time-plus-day") { clock.advance(DAY) }
                        Choice("初期時刻へ戻す", "time-reset") { clock.reset() }
                    }
                    catalog.workshops.forEach { workshop ->
                        HorizontalDivider()
                        Text(workshop.name, style = MaterialTheme.typography.titleSmall)
                        val deadline = workshop.start.value - DAY
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("取消期限の1ミリ秒前", "time-${workshop.id.value}-before-cancel-deadline") {
                                clock.set(EpochMillis(deadline - 1))
                            }
                            Choice("取消期限ちょうど", "time-${workshop.id.value}-at-cancel-deadline") {
                                clock.set(EpochMillis(deadline))
                            }
                            Choice("開始の1ミリ秒前", "time-${workshop.id.value}-before-start") {
                                clock.set(EpochMillis(workshop.start.value - 1))
                            }
                            Choice("開始ちょうど", "time-${workshop.id.value}-at-start") { clock.set(workshop.start) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Choice(label: String, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
}
