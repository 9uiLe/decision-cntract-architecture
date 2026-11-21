import Foundation
import Testing

@testable import ArchitectureRules

private func target(
    _ package: String, _ name: String, test: Bool = false, _ dependencies: [(String, String?)]
) -> TargetManifest {
    TargetManifest(
        package: package, name: name, isTest: test,
        dependencies: dependencies.map { .init(name: $0.0, package: $0.1) }
    )
}

@Suite
struct ManifestRuleTests {
    @Test func 許可した依存は通る() {
        let targets = [
            target("CheckInCore", "CheckInCore", [("Kernel", "Kernel"), ("ReservationCore", "ReservationCore")]),
            target("ReservationUI", "ReservationUI", [("Kernel", "Kernel"), ("CheckInCore", "CheckInCore")]),
        ]
        #expect(manifestViolations(targets).isEmpty)
    }

    @Test func 来場受付Coreが資格Coreに依存する宣言を禁止依存として検出する() {
        let targets = [target("CheckInCore", "CheckInCore", [("EligibilityCore", "EligibilityCore")])]
        #expect(manifestViolations(targets).map(\.rule) == ["package-dependency"])
    }

    @Test func 来場受付Coreのテストが資格Coreに依存する宣言も禁止依存として検出する() {
        let targets = [target("CheckInCore", "CheckInCoreTests", test: true, [("EligibilityCore", "EligibilityCore")])]
        #expect(manifestViolations(targets).map(\.rule) == ["package-dependency"])
    }

    @Test func 逆向きの依存と画面から代役への依存を検出する() {
        let reversed = [target("Kernel", "Kernel", [("ReservationCore", "ReservationCore")])]
        let screen = [target("ReservationUI", "ReservationUI", [("SimulatorAdapter", "SimulatorAdapter")])]
        #expect(manifestViolations(reversed).count == 1)
        #expect(manifestViolations(screen).count == 1)
    }

    @Test func 本番のターゲットがテスト支援に依存する宣言を検出しテストからは許す() {
        let production = [target("ReservationCore", "ReservationCore", [("KernelTestSupport", "Kernel")])]
        let test = [target("ReservationCore", "ReservationCoreTests", test: true, [("KernelTestSupport", "Kernel")])]
        #expect(manifestViolations(production).map(\.rule) == ["test-support"])
        #expect(manifestViolations(test).isEmpty)
    }

    @Test func 同じPackage内のターゲットへの依存は許す() {
        let targets = [
            target("Kernel", "Kernel", []),
            target("Kernel", "KernelTestSupport", [("Kernel", nil)]),
        ]
        #expect(manifestViolations(targets).isEmpty)
    }

    @Test func dump_packageのJSONからターゲットと依存を読む() throws {
        let json = """
            {"name": "CheckInCore", "targets": [
              {"name": "CheckInCore", "type": "regular", "dependencies": [{"product": ["Kernel", "Kernel", null, null]}]},
              {"name": "CheckInCoreTests", "type": "test", "dependencies": [{"byName": ["CheckInCore", null]}]}
            ]}
            """
        let targets = try parseManifest(Data(json.utf8))
        #expect(targets.map(\.name) == ["CheckInCore", "CheckInCoreTests"])
        #expect(targets[0].dependencies == [.init(name: "Kernel", package: "Kernel")])
        #expect(targets[1].isTest)
        #expect(targets[1].dependencies == [.init(name: "CheckInCore", package: nil)])
    }
}

@Suite
struct ImportRuleTests {
    private let checkIn = target("CheckInCore", "CheckInCore", [("Kernel", "Kernel"), ("ReservationCore", "ReservationCore")])

    @Test func 宣言した依存と自分と標準のフレームワークは通る() {
        let source = "import Kernel\npublic import ReservationCore\nimport Foundation\nimport os\n"
        #expect(importViolations(target: checkIn, ownModules: ["CheckInCore"], source: source, file: "f").isEmpty)
    }

    @Test func 宣言していない推移的な依存の_importを検出する() {
        let source = "import Kernel\nimport EligibilityCore\n"
        let violations = importViolations(target: checkIn, ownModules: ["CheckInCore"], source: source, file: "f")
        #expect(violations.map(\.rule) == ["undeclared-import"])
        #expect(violations.first?.location == "f:2")
    }

    @Test func 他のPackageのモジュールへの_testable_importを検出し自分のモジュールには許す() {
        let other = "@testable import ReservationCore\n"
        let own = "@testable import CheckInCore\n"
        #expect(importViolations(target: checkIn, ownModules: ["CheckInCore"], source: other, file: "f").map(\.rule) == ["cross-package-testable"])
        #expect(importViolations(target: checkIn, ownModules: ["CheckInCore"], source: own, file: "f").isEmpty)
    }

    @Test func アクセス修飾付きの_importも読む() {
        let source = "internal import Kernel\n@_exported import Foundation\nimport struct Kernel.SessionId\n"
        #expect(parseImports(source).map(\.module) == ["Kernel", "Foundation", "Kernel"])
    }

    @Test func アプリは組み立てのPackageだけを_importできる() {
        #expect(appImportViolations(source: "import Composition\nimport SwiftUI\n", file: "App").isEmpty)
        #expect(appImportViolations(source: "import ReservationCore\n", file: "App").map(\.rule) == ["app-import"])
        let allowed = "packages:\n  Composition:\n    path: x\ntargets:\n  A:\n    dependencies:\n      - package: Composition\n"
        #expect(appPackageViolations(projectYAML: allowed, file: "p").isEmpty)
        #expect(appPackageViolations(projectYAML: "      - package: ReservationCore\n", file: "p").map(\.rule) == ["app-package"])
    }
}

@Suite
struct CompileFixtureRuleTests {
    private let source = "// EXPECT: is inaccessible\nimport X\n"
    private let diagnostic = "f.swift:3:5: error: 'T' initializer is inaccessible due to 'internal' protection level\n"

    @Test func 期待した診断の_errorが一件だけなら通りnoteは数えない() {
        let withNote = diagnostic + "f.swift:9:1: note: 'init' declared here\n"
        #expect(rejectViolations(name: "n", source: source, status: 1, errorOutput: withNote).isEmpty)
    }

    @Test func コンパイラが同じ位置の同じ診断を重ねて出しても一件として数える() {
        #expect(rejectViolations(name: "n", source: source, status: 1, errorOutput: diagnostic + diagnostic).isEmpty)
    }

    @Test func 期待の記述が無い負例を拒否する() {
        #expect(rejectViolations(name: "n", source: "import X\n", status: 1, errorOutput: diagnostic).count == 1)
    }

    @Test func コンパイルできた負例を拒否する() {
        #expect(rejectViolations(name: "n", source: source, status: 0, errorOutput: "").count == 1)
    }

    @Test func 別の理由で失敗した負例を拒否する() {
        let other = "f.swift:1:8: error: no such module 'X'\n"
        #expect(rejectViolations(name: "n", source: source, status: 1, errorOutput: other).count == 1)
    }

    @Test func 期待した診断に加えて別の_errorが出る負例を拒否する() {
        let both = diagnostic + "f.swift:4:1: error: cannot find 'y' in scope\n"
        #expect(rejectViolations(name: "n", source: source, status: 1, errorOutput: both).count == 1)
    }
}

@Suite
struct EvidenceRuleTests {
    private let source = "public final class Ticket: IssuedEvidence {\n    init() {}\n}\n"

    @Test func 発行物を見つけて置き場所と形と負例を確かめる() {
        let declarations = evidenceDeclarations(source: source, file: "Packages/X/Sources/X/TicketEvidence.swift")
        #expect(declarations == [EvidenceDeclaration(type: "Ticket", file: "Packages/X/Sources/X/TicketEvidence.swift", isPublicFinalClass: true)])
        #expect(evidenceViolations(declarations, fixtureNames: ["ForgeTicket"]).isEmpty)
    }

    @Test func 置き場所と形と負例の欠けを検出する() {
        let misplaced = evidenceDeclarations(source: source, file: "Packages/X/Sources/X/Ticket.swift")
        #expect(evidenceViolations(misplaced, fixtureNames: ["ForgeTicket"]).map(\.rule) == ["evidence-location"])
        let open = evidenceDeclarations(source: "open class Ticket: IssuedEvidence {}\n", file: "TicketEvidence.swift")
        #expect(evidenceViolations(open, fixtureNames: ["ForgeTicket"]).map(\.rule) == ["evidence-shape"])
        let declarations = evidenceDeclarations(source: source, file: "TicketEvidence.swift")
        #expect(evidenceViolations(declarations, fixtureNames: []).map(\.rule) == ["evidence-fixture"])
    }

    @Test func SwiftLintの独自規則の名前を設定から読む() {
        let config = "included:\n  - A\ncustom_rules:\n  first_rule:\n    name: x\n  second_rule:\n    name: y\n"
        #expect(customRuleNames(config: config) == ["first_rule", "second_rule"])
    }
}
