import Foundation

public struct ImportStatement: Equatable, Sendable {
    public let module: String
    public let isTestable: Bool
    public let line: Int
}

public func parseImports(_ source: String) -> [ImportStatement] {
    let pattern = #"^\s*(?:@testable\s+)?(?:@_exported\s+)?(?:public\s+|internal\s+|package\s+|private\s+|fileprivate\s+)?import\s+(?:\w+\s+)?([A-Za-z_]\w*)"#
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
    return source.components(separatedBy: "\n").enumerated().compactMap { index, line in
        let range = NSRange(line.startIndex..., in: line)
        guard let match = regex.firstMatch(in: line, range: range), let module = Range(match.range(at: 1), in: line) else {
            return nil
        }
        return ImportStatement(module: String(line[module]), isTestable: line.contains("@testable"), line: index + 1)
    }
}

// `@testable import` は internal を見せるため、自分の Package にだけ許す。他の Package に使うと発行物の制限を迂回できる。
public func importViolations(
    target: TargetManifest,
    ownModules: Set<String>,
    source: String,
    file: String
) -> [Violation] {
    let declared = Set(target.dependencies.map(\.name))
    return parseImports(source).flatMap { statement -> [Violation] in
        let location = "\(file):\(statement.line)"
        var violations: [Violation] = []
        let allowed = statement.module == target.name || declared.contains(statement.module)
            || systemModules.contains(statement.module)
        if !allowed {
            violations.append(Violation(
                rule: "undeclared-import", location: location,
                message: "\(statement.module) は \(target.package)/\(target.name) の依存に宣言されていません"
            ))
        }
        if statement.isTestable && !ownModules.contains(statement.module) {
            violations.append(Violation(
                rule: "cross-package-testable", location: location,
                message: "@testable import は自分の Package のモジュールにだけ使えます: \(statement.module)"
            ))
        }
        return violations
    }
}

public func appImportViolations(source: String, file: String) -> [Violation] {
    parseImports(source).compactMap { statement in
        appAllowedImports.contains(statement.module)
            ? nil
            : Violation(
                rule: "app-import", location: "\(file):\(statement.line)",
                message: "App は組み立ての Package だけを import できます: \(statement.module)"
            )
    }
}

public func appPackageViolations(projectYAML: String, file: String) -> [Violation] {
    let regex = try? NSRegularExpression(pattern: #"^\s*-?\s*package:\s*(\w+)"#, options: .anchorsMatchLines)
    let range = NSRange(projectYAML.startIndex..., in: projectYAML)
    let packages = regex?.matches(in: projectYAML, range: range).compactMap { match -> String? in
        Range(match.range(at: 1), in: projectYAML).map { String(projectYAML[$0]) }
    } ?? []
    return Set(packages).subtracting(appAllowedPackages).sorted().map {
        Violation(rule: "app-package", location: file, message: "App が組み立て以外の Package \($0) に直接依存しています")
    }
}
