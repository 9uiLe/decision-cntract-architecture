import Foundation

private let upcomingFeatures = ["ExistentialAny", "InternalImportsByDefault", "MemberImportVisibility"]

private func swiftFiles(in directory: URL) -> [URL] {
    let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
    return (enumerator?.allObjects as? [URL] ?? []).filter { $0.pathExtension == "swift" }.sorted { $0.path < $1.path }
}

private func expectation(_ source: String) -> String? {
    source.components(separatedBy: "\n").first { $0.hasPrefix("// EXPECT:") }
        .map { $0.dropFirst("// EXPECT:".count).trimmingCharacters(in: .whitespaces) }
}

public func compileFixtureNames(root: URL) -> Set<String> {
    Set(swiftFiles(in: root.appendingPathComponent("reject")).map { $0.deletingPathExtension().lastPathComponent })
}

public func compileFixtureViolations(root: URL, modulesDirectory: String) throws -> [Violation] {
    var violations: [Violation] = []
    for (kind, shouldCompile) in [("accept", true), ("reject", false)] {
        for file in swiftFiles(in: root.appendingPathComponent(kind)) {
            let source = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
            var arguments = ["swiftc", "-typecheck", "-swift-version", "6", "-I", modulesDirectory]
            for feature in upcomingFeatures { arguments += ["-enable-upcoming-feature", feature] }
            arguments.append(file.path)
            let result = try run("/usr/bin/env", arguments)
            let name = "\(kind)/\(file.lastPathComponent)"
            if shouldCompile {
                if result.status != 0 {
                    violations.append(Violation(rule: "compile-accept", location: name, message: "正例がコンパイルできません: \(result.errorOutput.prefix(400))"))
                }
                continue
            }
            violations += rejectViolations(name: name, source: source, status: result.status, errorOutput: result.errorOutput)
        }
    }
    return violations
}

// 期待の無い負例や別の理由での失敗を許すと、負例が検査したい制約と無関係な理由で通ってしまう。note は数えない。
func rejectViolations(name: String, source: String, status: Int32, errorOutput: String) -> [Violation] {
    guard let expected = expectation(source), !expected.isEmpty else {
        return [Violation(rule: "compile-reject", location: name, message: "負例の先頭に期待する診断の「// EXPECT: <診断の一部>」が必要です")]
    }
    if status == 0 {
        return [Violation(rule: "compile-reject", location: name, message: "負例がコンパイルできてしまいました")]
    }
    var seen: Set<String> = []
    let errors = errorOutput.components(separatedBy: "\n")
        .filter { $0.range(of: #"^\S+:\d+:\d+: error: "#, options: .regularExpression) != nil }
        .filter { seen.insert($0).inserted }
    guard errors.count == 1, let error = errors.first, error.contains(expected) else {
        return [Violation(
            rule: "compile-reject", location: name,
            message: "期待した診断「\(expected)」の error が一件だけ出るはずですが、\(errors.count) 件でした: \(errors.joined(separator: " / ").prefix(500))"
        )]
    }
    return []
}

private struct LintFinding: Hashable {
    let file: String
    let line: Int
    let rule: String
}

public func customRuleNames(config: String) -> Set<String> {
    guard let section = config.range(of: "custom_rules:") else { return [] }
    let regex = try? NSRegularExpression(pattern: #"^  (\w+):\s*$"#, options: .anchorsMatchLines)
    let body = String(config[section.upperBound...])
    let range = NSRange(body.startIndex..., in: body)
    return Set(regex?.matches(in: body, range: range).compactMap { match in
        Range(match.range(at: 1), in: body).map { String(body[$0]) }
    } ?? [])
}

public func lintFixtureViolations(root: URL, swiftlint: String, config: URL) throws -> [Violation] {
    let rules = customRuleNames(config: (try? String(contentsOf: config, encoding: .utf8)) ?? "")
    var violations: [Violation] = []
    for kind in ["accept", "reject"] {
        let directory = root.appendingPathComponent(kind)
        let result = try run(swiftlint, [
            "lint", "--config", config.path, "--no-cache", "--quiet", "--reporter", "json", directory.path,
        ])
        guard let data = result.output.data(using: .utf8),
            let json = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            violations.append(Violation(rule: "lint-fixture", location: kind, message: "SwiftLint の出力を読めません: \(result.errorOutput.prefix(300))"))
            continue
        }
        let actual = Set(json.compactMap { entry -> LintFinding? in
            guard let file = entry["file"] as? String, let line = entry["line"] as? Int, let rule = entry["rule_id"] as? String,
                rules.contains(rule)
            else { return nil }
            return LintFinding(file: relative(file, to: directory), line: line, rule: rule)
        })
        let expected = kind == "reject" ? try expectedFindings(directory) : []
        for missing in expected.subtracting(actual) {
            violations.append(Violation(rule: "lint-reject", location: "\(kind)/\(missing.file):\(missing.line)", message: "\(missing.rule) が検出されません"))
        }
        for unexpected in actual.subtracting(expected) {
            violations.append(Violation(rule: "lint-\(kind)", location: "\(kind)/\(unexpected.file):\(unexpected.line)", message: "想定外の検出 \(unexpected.rule)"))
        }
    }
    return violations
}

private func relative(_ path: String, to directory: URL) -> String {
    let base = directory.resolvingSymlinksInPath().path + "/"
    let resolved = URL(fileURLWithPath: path).resolvingSymlinksInPath().path
    return resolved.hasPrefix(base) ? String(resolved.dropFirst(base.count)) : path
}

private func expectedFindings(_ directory: URL) throws -> Set<LintFinding> {
    var findings: Set<LintFinding> = []
    for file in swiftFiles(in: directory) {
        let source = try String(contentsOf: file, encoding: .utf8)
        for (index, line) in source.components(separatedBy: "\n").enumerated() {
            guard let marker = line.range(of: "// EXPECT:") else { continue }
            let rule = line[marker.upperBound...].trimmingCharacters(in: .whitespaces)
            findings.insert(LintFinding(file: relative(file.path, to: directory), line: index + 1, rule: rule))
        }
    }
    return findings
}
