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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import example.adapter.simulator.CancelBehavior
import example.adapter.simulator.CheckInBehavior
import example.adapter.simulator.CreateBehavior
import example.adapter.simulator.LookupBehavior
import example.adapter.simulator.QualificationBehavior
import example.adapter.simulator.SimulatorControls
import example.adapter.simulator.UsageBehavior
import example.app.composition.ControllableClock
import example.kernel.EpochMillis
import example.reservation.core.Catalog
import example.reservation.core.NewBookingRequirement
import example.reservation.ui.exposeTestTags
import example.reservation.ui.formatPrecise

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR
private const val CHECK_IN_OPENS = 30L * 60 * 1000
private const val CHECK_IN_CLOSES = 15L * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun DebugControlScreen(controls: SimulatorControls, clock: ControllableClock, catalog: Catalog) {
    val observations by controls.observations.collectAsStateWithLifecycle()
    val now by clock.time.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize().exposeTestTags()) {
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
                        "資格照会 ${observations.qualificationRequests} 回 ・ 予約の現在状態の照会 ${observations.usageRequests} 回",
                        modifier = Modifier.testTag("observe-qualification-usage"),
                    )
                    Text(
                        "受付作成要求 ${observations.checkInCreateRequests} 回 ・ 受付照会 ${observations.checkInLookupRequests} 回 ・ 受付済みへの重複要求 ${observations.checkInRepeatedForRecordedTarget} 回",
                        modifier = Modifier.testTag("observe-checkin-requests"),
                    )
                    Text(
                        "受付記録 " + (observations.checkInRecords.takeIf { it.isNotEmpty() }
                            ?.joinToString("、") { "${it.reservation.value}/${it.participant.value} ${it.records} 件" } ?: "なし"),
                        modifier = Modifier.testTag("observe-checkin-records"),
                    )
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
                Section("次の資格照会の応答") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("記録どおり", "qualification-normal") { controls.enqueueQualification(QualificationBehavior.Normal) }
                        Choice("応答なし", "qualification-no-response") { controls.enqueueQualification(QualificationBehavior.NoResponse) }
                        Choice("解釈できない応答", "qualification-unrecognized") {
                            controls.enqueueQualification(QualificationBehavior.Unrecognized)
                        }
                        Choice("保留", "qualification-hold") { controls.enqueueQualification(QualificationBehavior.Hold) }
                    }
                }
                Section("資格の保有状況") {
                    val kinds = catalog.workshops.mapNotNull { (it.requirement as? NewBookingRequirement.Required)?.kind }.distinct()
                    catalog.participants.forEach { participant ->
                        kinds.forEach { kind ->
                            HorizontalDivider()
                            Text("${participant.name} ・ ${kind.name}", style = MaterialTheme.typography.titleSmall)
                            val tag = "${participant.id.value}-${kind.id}"
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Choice("付与（長期間有効）", "grant-$tag") {
                                    controls.grantQualification(participant.id, kind, EpochMillis(now.value + 30 * DAY))
                                }
                                Choice("期限を現在時刻にする", "expire-$tag") {
                                    controls.grantQualification(participant.id, kind, now)
                                }
                                Choice("付与を取り消す", "revoke-$tag") { controls.revokeQualification(participant.id, kind) }
                            }
                        }
                    }
                }
                Section("次の予約の現在状態の照会") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("記録どおり", "usage-normal") { controls.enqueueUsage(UsageBehavior.Normal) }
                        Choice("照会に失敗", "usage-fail") { controls.enqueueUsage(UsageBehavior.Fail) }
                        Choice("保留", "usage-hold") { controls.enqueueUsage(UsageBehavior.Hold) }
                    }
                }
                Section("次の受付作成の応答") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("通常", "checkin-succeed") { controls.enqueueCheckInCreate(CheckInBehavior.Succeed) }
                        Choice("作成前に拒否", "checkin-reject") { controls.enqueueCheckInCreate(CheckInBehavior.RejectBeforeRecord) }
                        Choice("記録して応答喪失", "checkin-lose") { controls.enqueueCheckInCreate(CheckInBehavior.LoseResponseAfterRecord) }
                        Choice("処理中のまま保留", "checkin-hold") { controls.enqueueCheckInCreate(CheckInBehavior.HoldResponse) }
                    }
                }
                Section("次の受付照会の応答") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("記録どおり", "checkin-lookup-normal") { controls.enqueueCheckInLookup(LookupBehavior.Normal) }
                        Choice("記録が見つからない", "checkin-lookup-not-found") { controls.enqueueCheckInLookup(LookupBehavior.NotFound) }
                        Choice("照会に失敗", "checkin-lookup-fail") { controls.enqueueCheckInLookup(LookupBehavior.Fail) }
                        Choice("保留", "checkin-lookup-hold") { controls.enqueueCheckInLookup(LookupBehavior.Hold) }
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
                            Choice("受付開始の1ミリ秒前", "time-${workshop.id.value}-before-checkin-opens") {
                                clock.set(EpochMillis(workshop.start.value - CHECK_IN_OPENS - 1))
                            }
                            Choice("受付開始ちょうど", "time-${workshop.id.value}-at-checkin-opens") {
                                clock.set(EpochMillis(workshop.start.value - CHECK_IN_OPENS))
                            }
                            Choice("受付終了の1ミリ秒前", "time-${workshop.id.value}-before-checkin-closes") {
                                clock.set(EpochMillis(workshop.start.value + CHECK_IN_CLOSES - 1))
                            }
                            Choice("受付終了ちょうど", "time-${workshop.id.value}-at-checkin-closes") {
                                clock.set(EpochMillis(workshop.start.value + CHECK_IN_CLOSES))
                            }
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
