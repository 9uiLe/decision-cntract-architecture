import Combine
import EligibilityCore
import Kernel
import ReservationCore
import SimulatorAdapter
public import SwiftUI
import ReservationUI

private let hour: Int64 = 3_600_000
private let day: Int64 = 24 * hour
private let checkInOpens: Int64 = 30 * 60 * 1000
private let checkInCloses: Int64 = 15 * 60 * 1000

// 通常の予約操作とは別の入口。応答と時刻の操作、要求回数の観測だけを持ち、Core の操作は呼ばない。
@MainActor
final class DebugControlModel: ObservableObject {
    @Published private(set) var observations: SimulatorObservations
    @Published private(set) var now: EpochMillis
    let controls: any SimulatorControls
    let clock: ControllableClock
    private var tasks: [Task<Void, Never>] = []

    init(controls: any SimulatorControls, clock: ControllableClock) {
        self.controls = controls
        self.clock = clock
        observations = controls.observations
        now = clock.now()
    }

    func start() {
        guard tasks.isEmpty else { return }
        let observed = controls.observationUpdates()
        let times = clock.updates()
        tasks = [
            Task { [weak self] in
                for await value in observed {
                    guard let self else { return }
                    observations = value
                }
            },
            Task { [weak self] in
                for await value in times {
                    guard let self else { return }
                    now = value
                }
            },
        ]
    }
}

public struct DebugControlView: View {
    @StateObject private var model: DebugControlModel
    private let catalog: Catalog

    public init(container: AppContainer) {
        _model = StateObject(wrappedValue: DebugControlModel(controls: container.simulatorControls, clock: container.clock))
        catalog = container.catalog
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    observationSection
                    behaviorSections
                    qualificationSection
                    releaseSection
                    timeSection
                }
                .padding()
            }
            .navigationTitle("検証用の操作")
        }
        .task { model.start() }
    }

    private var controls: any SimulatorControls { model.controls }

    private var observationSection: some View {
        let o = model.observations
        return Section("観測") {
            Text("現在時刻 \(formatPrecise(model.now))").accessibilityIdentifier("observe-now")
            Text("作成要求 \(o.createRequests) 回 ・ 照会 \(o.lookUpRequests) 回 ・ 取消 \(o.cancelRequests) 回")
                .accessibilityIdentifier("observe-requests")
            Text("成立している予約 \(o.activeReservations) 件").accessibilityIdentifier("observe-reservations")
            Text("資格照会 \(o.qualificationRequests) 回 ・ 予約の現在状態の照会 \(o.usageRequests) 回")
                .accessibilityIdentifier("observe-qualification-usage")
            Text("受付作成要求 \(o.checkInCreateRequests) 回 ・ 受付照会 \(o.checkInLookUpRequests) 回 ・ 受付済みへの重複要求 \(o.checkInRepeatedForRecordedTarget) 回")
                .accessibilityIdentifier("observe-checkin-requests")
            Text("受付記録 " + (o.checkInRecords.isEmpty
                ? "なし"
                : o.checkInRecords.map { "\($0.reservation.value)/\($0.participant.value) \($0.records) 件" }.joined(separator: "、")))
                .accessibilityIdentifier("observe-checkin-records")
            Text("保留中の呼び出し \(o.heldCalls) 件 ・ 台本の待ち 作成 \(o.queuedCreate) / 照会 \(o.queuedLookUp) / 取消 \(o.queuedCancel)")
                .accessibilityIdentifier("observe-held")
        }
    }

    @ViewBuilder private var behaviorSections: some View {
        Section("次の予約作成の応答") {
            choices([
                ("通常成立", "create-succeed", { controls.enqueueCreate(.succeed) }),
                ("満席", "create-sold-out", { controls.enqueueCreate(.soldOut) }),
                ("作成前に拒否", "create-reject", { controls.enqueueCreate(.rejectBeforeCreate) }),
                ("成立して応答喪失", "create-lose", { controls.enqueueCreate(.loseResponseAfterCreate) }),
                ("処理中のまま保留", "create-hold", { controls.enqueueCreate(.holdResponse) }),
            ])
        }
        Section("次の結果照会の応答") {
            choices([
                ("記録どおり", "lookup-normal", { controls.enqueueLookUp(.normal) }),
                ("記録が見つからない", "lookup-not-found", { controls.enqueueLookUp(.notFound) }),
                ("照会に失敗", "lookup-fail", { controls.enqueueLookUp(.fail) }),
                ("保留", "lookup-hold", { controls.enqueueLookUp(.hold) }),
            ])
        }
        Section("次の取消の応答") {
            choices([
                ("通常", "cancel-normal", { controls.enqueueCancel(.normal) }),
                ("取消を拒否", "cancel-decline", { controls.enqueueCancel(.decline) }),
                ("成立して応答喪失", "cancel-lose", { controls.enqueueCancel(.lose) }),
                ("保留", "cancel-hold", { controls.enqueueCancel(.hold) }),
            ])
        }
        Section("次の資格照会の応答") {
            choices([
                ("記録どおり", "qualification-normal", { controls.enqueueQualification(.normal) }),
                ("応答なし", "qualification-no-response", { controls.enqueueQualification(.noResponse) }),
                ("解釈できない応答", "qualification-unrecognized", { controls.enqueueQualification(.unrecognized) }),
                ("保留", "qualification-hold", { controls.enqueueQualification(.hold) }),
            ])
        }
        Section("次の予約の現在状態の照会") {
            choices([
                ("記録どおり", "usage-normal", { controls.enqueueUsage(.normal) }),
                ("照会に失敗", "usage-fail", { controls.enqueueUsage(.fail) }),
                ("保留", "usage-hold", { controls.enqueueUsage(.hold) }),
            ])
        }
        Section("次の受付作成の応答") {
            choices([
                ("通常", "checkin-succeed", { controls.enqueueCheckInCreate(.succeed) }),
                ("作成前に拒否", "checkin-reject", { controls.enqueueCheckInCreate(.rejectBeforeRecord) }),
                ("記録して応答喪失", "checkin-lose", { controls.enqueueCheckInCreate(.loseResponseAfterRecord) }),
                ("処理中のまま保留", "checkin-hold", { controls.enqueueCheckInCreate(.holdResponse) }),
            ])
        }
        Section("次の受付照会の応答") {
            choices([
                ("記録どおり", "checkin-lookup-normal", { controls.enqueueCheckInLookUp(.normal) }),
                ("記録が見つからない", "checkin-lookup-not-found", { controls.enqueueCheckInLookUp(.notFound) }),
                ("照会に失敗", "checkin-lookup-fail", { controls.enqueueCheckInLookUp(.fail) }),
                ("保留", "checkin-lookup-hold", { controls.enqueueCheckInLookUp(.hold) }),
            ])
        }
    }

    private var qualificationSection: some View {
        let kinds = catalog.workshops.compactMap { workshop -> QualificationKind? in
            if case .required(let kind) = workshop.requirement { return kind }
            return nil
        }
        let uniqueKinds = kinds.reduce(into: [QualificationKind]()) { if !$0.contains($1) { $0.append($1) } }
        return Section("資格の保有状況") {
            ForEach(catalog.participants, id: \.id.value) { participant in
                ForEach(uniqueKinds, id: \.id) { kind in
                    let tag = "\(participant.id.value)-\(kind.id)"
                    Divider()
                    Text("\(participant.name) ・ \(kind.name)").font(.subheadline.bold())
                    choices([
                        ("付与（長期間有効）", "grant-\(tag)", {
                            controls.grantQualification(
                                participant: participant.id, kind: kind, expiresAt: model.now.advanced(byMillis: 30 * day)
                            )
                        }),
                        ("期限を現在時刻にする", "expire-\(tag)", {
                            controls.grantQualification(participant: participant.id, kind: kind, expiresAt: model.now)
                        }),
                        ("付与を取り消す", "revoke-\(tag)", {
                            controls.revokeQualification(participant: participant.id, kind: kind)
                        }),
                    ])
                }
            }
        }
    }

    private var releaseSection: some View {
        Section("保留と初期化") {
            HStack {
                Button("保留を解放") { controls.releaseHeld() }
                    .buttonStyle(.borderedProminent).accessibilityIdentifier("release-held")
                Button("シミュレーターを初期化") { controls.reset() }
                    .buttonStyle(.bordered).accessibilityIdentifier("reset-simulator")
            }
        }
    }

    private var timeSection: some View {
        Section("時刻") {
            choices([
                ("1時間進める", "time-plus-hour", { model.clock.advance(millis: hour) }),
                ("1時間戻す", "time-minus-hour", { model.clock.advance(millis: -hour) }),
                ("1日進める", "time-plus-day", { model.clock.advance(millis: day) }),
                ("初期時刻へ戻す", "time-reset", { model.clock.reset() }),
            ])
            ForEach(catalog.workshops, id: \.id.value) { workshop in
                let id = workshop.id.value
                let begin = workshop.start
                Divider()
                Text(workshop.name).font(.subheadline.bold())
                choices([
                    ("取消期限の1ミリ秒前", "time-\(id)-before-cancel-deadline", { set(begin.advanced(byMillis: -day - 1)) }),
                    ("取消期限ちょうど", "time-\(id)-at-cancel-deadline", { set(begin.advanced(byMillis: -day)) }),
                    ("開始の1ミリ秒前", "time-\(id)-before-start", { set(begin.advanced(byMillis: -1)) }),
                    ("開始ちょうど", "time-\(id)-at-start", { set(begin) }),
                    ("受付開始の1ミリ秒前", "time-\(id)-before-checkin-opens", { set(begin.advanced(byMillis: -checkInOpens - 1)) }),
                    ("受付開始ちょうど", "time-\(id)-at-checkin-opens", { set(begin.advanced(byMillis: -checkInOpens)) }),
                    ("受付終了の1ミリ秒前", "time-\(id)-before-checkin-closes", { set(begin.advanced(byMillis: checkInCloses - 1)) }),
                    ("受付終了ちょうど", "time-\(id)-at-checkin-closes", { set(begin.advanced(byMillis: checkInCloses)) }),
                ])
            }
        }
    }

    private func set(_ time: EpochMillis) { model.clock.set(time) }

    private func choices(_ items: [(String, String, () -> Void)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8, alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.1) { item in
                Button(item.0, action: item.2).buttonStyle(.bordered).accessibilityIdentifier(item.1)
            }
        }
    }
}

private struct Section<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            content
        }
    }
}
