import XCTest

// 表示（文言）が同じまま、券か実行先だけが替わる更新の直後にボタンをクリックし、操作が届いた先を観測する。
// 識別の根拠は各テストに書く。画面には券の識別子を出さず、境界の外側の観測欄（observe-*）だけを読む。
// product は製品の ReservationStatusView と ActionButton をそのまま使う。それ以外は、避けたい保持の仕方を同じ文言で再現した負例。
final class ActionClickUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private static let staleMessage = "画面の内容が古くなっています。最新の状態を確認してください。"
    private static let busyMessage = "処理中、または結果を確認できていない予約操作があります。先にその状況を確認してください。"

    @MainActor
    private func launch(_ scenario: String, _ variant: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-scenario", scenario, "-variant", variant]
        app.launch()
        waitFor(app, "observe-phase", "phase=ready")
        return app
    }

    // 観測欄の表示が期待どおりになるまで待つ。時間切れ（.completed 以外）や欄が無いときは失敗にする。
    @MainActor
    private func waitFor(_ app: XCUIApplication, _ identifier: String, _ expected: String, line: UInt = #line) {
        let element = app.staticTexts[identifier]
        let result = XCTWaiter().wait(
            for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", expected), object: element)], timeout: 10
        )
        XCTAssertEqual(result, .completed, "\(identifier) が \(expected) になること", line: line)
        XCTAssertTrue(element.exists, "\(identifier) の観測欄があること", line: line)
        XCTAssertEqual(element.label, expected, line: line)
    }

    @MainActor
    private func value(_ app: XCUIApplication, _ identifier: String) -> String {
        let element = app.staticTexts[identifier]
        return element.exists ? element.label : ""
    }

    // 拒否の通知は、期待する文言そのものが観測欄に出るまで待つ。空や「none」は通らない。
    @MainActor
    private func waitForNotice(_ app: XCUIApplication, _ identifier: String, _ message: String, line: UInt = #line) {
        let side = identifier.hasSuffix("-a") ? "notice-a" : "notice-b"
        waitFor(app, identifier, "\(side)=\(message)", line: line)
    }

    @MainActor
    private func card(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    // 更新の前に、同じ画面の中の状態を動かす。ホストの @State（host-count）と、スクロール位置。
    @MainActor
    private func keepState(_ app: XCUIApplication, card identifier: String) -> CGFloat {
        app.buttons["host-count"].tap()
        XCTAssertEqual(app.buttons["host-count"].label, "host=1")
        app.scrollViews.firstMatch.swipeUp()
        let top = card(app, identifier).frame.minY
        return top
    }

    @MainActor
    private func assertSameScreen(_ app: XCUIApplication, card identifier: String, top: CGFloat, heading: String, button: String) {
        XCTAssertTrue(app.staticTexts[heading].exists, "表示の見出し「\(heading)」が同じ")
        XCTAssertTrue(app.buttons[button].exists, "ボタンの文言「\(button)」が同じ")
        XCTAssertEqual(app.buttons["host-count"].label, "host=1", "同じ画面の @State を維持")
        XCTAssertEqual(card(app, identifier).frame.minY, top, accuracy: 1, "スクロール位置を維持")
    }

    // 更新の公開を、観測欄（外側の status が持つ券と実行先）の表示で待ってから、同じ画面の状態を確かめる。
    @MainActor
    private func publish(_ app: XCUIApplication, button: String, expecting published: String) {
        app.buttons[button].tap()
        waitFor(app, "observe-offered", published.replacingOccurrences(of: "published=", with: "offered="))
        waitFor(app, "observe-published", published)
    }

    // 更新が流れの途中で落ちたこと（渡した値は届かず、前の値のまま）を観測してから、同じ画面を操作する。
    @MainActor
    private func offerDropped(_ app: XCUIApplication, button: String, offered: String, stillPublished: String) {
        app.buttons[button].tap()
        waitFor(app, "observe-offered", offered)
        waitFor(app, "observe-published", stillPublished)
    }

    @MainActor
    private func update(_ app: XCUIApplication, card identifier: String, heading: String, button: String) {
        let top = keepState(app, card: identifier)
        publish(app, button: "show-t2", expecting: "published=second@A")
        assertSameScreen(app, card: identifier, top: top, heading: heading, button: button)
    }

    // MARK: 券だけが替わる（同じ要求）

    // 識別: 照会の要求が1回増えたことで、Core が新しい券を === で受理したと分かる。古い券なら拒否されて要求は増えない。
    @MainActor
    func test_同じ表示で券だけが替わっても_更新後の照会は新しい券で届く() {
        let app = launch("ticket", "product")
        waitFor(app, "observe-lookups", "lookups=1")
        waitFor(app, "observe-published", "published=first@A")
        update(app, card: "request-unknown", heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitFor(app, "observe-lookups", "lookups=2")
        waitFor(app, "observe-core-reservations", "core-reservations=1")
        XCTAssertEqual(value(app, "observe-notice-a"), "notice-a=none")
    }

    @MainActor
    func test_表示だけをキーにした保持は_券が替わった後の照会を拒否される() {
        let app = launch("ticket", "heldInState")
        waitFor(app, "observe-lookups", "lookups=1")
        update(app, card: "request-unknown", heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitForNotice(app, "observe-notice-a", Self.staleMessage)
        waitFor(app, "observe-lookups", "lookups=1")
        waitFor(app, "observe-core-reservations", "core-reservations=0")
    }

    @MainActor
    func test_表示の等価性で更新を落とす流れは_券が替わった後の照会を拒否される() {
        let app = launch("ticket", "removeDuplicates")
        waitFor(app, "observe-lookups", "lookups=1")
        let top = keepState(app, card: "request-unknown")
        offerDropped(app, button: "show-t2", offered: "offered=second@A", stillPublished: "published=first@A")
        assertSameScreen(app, card: "request-unknown", top: top, heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitForNotice(app, "observe-notice-a", Self.staleMessage)
        waitFor(app, "observe-lookups", "lookups=1")
    }

    // MARK: 実行先の要求が替わる（再試行で別の RequestId）

    // 識別: Simulator が受けた最後の照会の RequestId が、更新後の券の RequestId（second）と一致する。
    // 古い要求の券なら、受理されずに照会は増えない。
    @MainActor
    func test_同じ結果不明の表示でも_再試行で替わった後の照会は新しい要求へ届く() {
        let app = launch("request", "product")
        waitFor(app, "observe-lookups", "lookups=1")
        waitFor(app, "observe-lookup-target", "lookup-target=first")
        update(app, card: "request-unknown", heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitFor(app, "observe-lookups", "lookups=2")
        waitFor(app, "observe-lookup-target", "lookup-target=second")
        waitFor(app, "observe-core-reservations", "core-reservations=1")
    }

    @MainActor
    func test_表示だけをキーにした保持は_新しい要求ではなく古い要求の券で拒否される() {
        let app = launch("request", "heldInState")
        waitFor(app, "observe-lookups", "lookups=1")
        update(app, card: "request-unknown", heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitForNotice(app, "observe-notice-a", Self.staleMessage)
        waitFor(app, "observe-lookups", "lookups=1")
        waitFor(app, "observe-lookup-target", "lookup-target=first")
    }

    // MARK: 券は同じで実行先（ViewModel）が替わる

    // 識別: 同じ券を二度使うと、Core は実行中の照会と同じ券として Busy で拒否し、通知は実行した ViewModel にだけ出る。
    // 実行先 B へ替えた後の拒否の通知が notice-b に出て、notice-a には出ない。
    @MainActor
    func test_同じ券でも実行先が替わったら_更新後の照会は新しい実行先へ届く() {
        let app = launch("endpoint", "product")
        app.buttons["lookup-button"].tap()
        waitFor(app, "observe-lookups", "lookups=1")
        let top = keepState(app, card: "request-unknown")
        publish(app, button: "show-endpoint-b", expecting: "published=same@B")
        assertSameScreen(app, card: "request-unknown", top: top, heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitForNotice(app, "observe-notice-b", Self.busyMessage)
        XCTAssertEqual(value(app, "observe-notice-a"), "notice-a=none")
        waitFor(app, "observe-lookups", "lookups=1")
    }

    @MainActor
    func test_券だけをキーにした保持は_実行先が替わった後も古い実行先へ届く() {
        let app = launch("endpoint", "heldByTicket")
        app.buttons["lookup-button"].tap()
        waitFor(app, "observe-lookups", "lookups=1")
        let top = keepState(app, card: "request-unknown")
        publish(app, button: "show-endpoint-b", expecting: "published=same@B")
        assertSameScreen(app, card: "request-unknown", top: top, heading: "結果不明", button: "状況を照会する")
        app.buttons["lookup-button"].tap()
        waitForNotice(app, "observe-notice-a", Self.busyMessage)
        XCTAssertEqual(value(app, "observe-notice-b"), "notice-b=none")
    }
}
