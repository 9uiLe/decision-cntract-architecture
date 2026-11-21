import ArchitectureRules
import Foundation

private let usage = "使い方: architecture-check <manifest|app|evidence|compile|lint-fixtures|all> [--root <reservation-ios>] [--swiftlint <path>]"

private func option(_ name: String) -> String? {
    guard let index = CommandLine.arguments.firstIndex(of: name), index + 1 < CommandLine.arguments.count else { return nil }
    return CommandLine.arguments[index + 1]
}

private func execute() -> Int32 {
    guard CommandLine.arguments.count > 1 else {
        print(usage)
        return 2
    }
    let root = URL(fileURLWithPath: option("--root") ?? FileManager.default.currentDirectoryPath)
    let checker = Checker(root: root)
    let swiftlint = option("--swiftlint") ?? ProcessInfo.processInfo.environment["SWIFTLINT"]
        ?? root.appendingPathComponent(".tools/swiftlint").path

    let checks: [String: () throws -> [Violation]] = [
        "manifest": checker.manifestAndImportViolations,
        "app": checker.appViolations,
        "evidence": checker.evidenceViolations,
        "compile": checker.compileViolations,
        "lint-fixtures": {
            guard FileManager.default.isExecutableFile(atPath: swiftlint) else {
                return [Violation(
                    rule: "swiftlint", location: swiftlint,
                    message: "SwiftLint が実行できません。sh Tools/bootstrap-swiftlint.sh を実行するか、--swiftlint で指定してください"
                )]
            }
            return try checker.lintFixtureViolations(swiftlint: swiftlint)
        },
    ]

    let command = CommandLine.arguments[1]
    let selected = command == "all" ? ["manifest", "app", "evidence", "compile", "lint-fixtures"] : [command]
    var failed = false
    for name in selected {
        guard let check = checks[name] else {
            print(usage)
            return 2
        }
        do {
            let violations = try check()
            print("\(violations.isEmpty ? "OK  " : "NG  ") \(name) (\(violations.count) 件)")
            for violation in violations { print("    \(violation)") }
            failed = failed || !violations.isEmpty
        } catch {
            print("NG   \(name): \(error)")
            failed = true
        }
    }
    return failed ? 1 : 0
}

exit(execute())
