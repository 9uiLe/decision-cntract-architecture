import Foundation

public struct EvidenceDeclaration: Equatable, Sendable {
    public let type: String
    public let file: String
    public let isPublicFinalClass: Bool
}

public func evidenceDeclarations(source: String, file: String) -> [EvidenceDeclaration] {
    let pattern = #"^\s*((?:public\s+|internal\s+|open\s+)?(?:final\s+)?)class\s+(\w+)\s*:[^{]*\bIssuedEvidence\b"#
    guard let regex = try? NSRegularExpression(pattern: pattern, options: .anchorsMatchLines) else { return [] }
    let range = NSRange(source.startIndex..., in: source)
    return regex.matches(in: source, range: range).compactMap { match in
        guard let modifiers = Range(match.range(at: 1), in: source), let name = Range(match.range(at: 2), in: source) else {
            return nil
        }
        let words = Set(source[modifiers].split(separator: " ").map(String.init))
        return EvidenceDeclaration(
            type: String(source[name]), file: file, isPublicFinalClass: words.contains("public") && words.contains("final")
        )
    }
}

public func evidenceViolations(_ declarations: [EvidenceDeclaration], fixtureNames: Set<String>) -> [Violation] {
    declarations.flatMap { declaration -> [Violation] in
        var violations: [Violation] = []
        if !declaration.file.hasSuffix("Evidence.swift") {
            violations.append(Violation(
                rule: "evidence-location", location: declaration.file,
                message: "\(declaration.type) は IssuedEvidence です。*Evidence.swift に置いてください"
            ))
        }
        if !declaration.isPublicFinalClass {
            violations.append(Violation(
                rule: "evidence-shape", location: declaration.file,
                message: "\(declaration.type) は public final class で宣言してください"
            ))
        }
        if !fixtureNames.contains("Forge\(declaration.type)") {
            violations.append(Violation(
                rule: "evidence-fixture", location: declaration.file,
                message: "\(declaration.type) に外部から構築できないことを確かめる負例 Forge\(declaration.type).swift がありません"
            ))
        }
        return violations
    }
}
