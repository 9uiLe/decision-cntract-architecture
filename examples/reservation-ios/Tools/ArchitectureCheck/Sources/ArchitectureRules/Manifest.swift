import Foundation

public struct TargetManifest: Equatable, Sendable {
    public struct Dependency: Equatable, Sendable {
        public let name: String
        public let package: String?
    }

    public let package: String
    public let name: String
    public let isTest: Bool
    public let dependencies: [Dependency]
}

public enum ManifestError: Error { case unreadable(String) }

public func parseManifest(_ json: Data) throws -> [TargetManifest] {
    guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
        let package = root["name"] as? String,
        let targets = root["targets"] as? [[String: Any]]
    else { throw ManifestError.unreadable("dump-package の形式を読めません") }
    return targets.compactMap { target in
        guard let name = target["name"] as? String else { return nil }
        let dependencies = (target["dependencies"] as? [[String: Any]] ?? []).compactMap(dependency)
        return TargetManifest(package: package, name: name, isTest: target["type"] as? String == "test", dependencies: dependencies)
    }
}

private func dependency(_ raw: [String: Any]) -> TargetManifest.Dependency? {
    if let product = raw["product"] as? [Any], let name = product.first as? String {
        return .init(name: name, package: product.dropFirst().first as? String)
    }
    if let named = (raw["byName"] ?? raw["target"]) as? [Any], let name = named.first as? String {
        return .init(name: name, package: nil)
    }
    return nil
}

public func manifestViolations(_ targets: [TargetManifest]) -> [Violation] {
    let ownTargets = Dictionary(grouping: targets, by: \.package).mapValues { Set($0.map(\.name)) }
    var violations: [Violation] = []
    for target in targets {
        guard let allowed = allowedPackageDependencies[target.package] else {
            violations.append(Violation(rule: "manifest", location: target.package, message: "方針に無い Package です"))
            continue
        }
        for dependency in target.dependencies {
            let location = "\(target.package)/\(target.name)"
            guard let package = dependency.package ?? ownPackage(of: dependency.name, in: ownTargets, from: target.package) else {
                violations.append(Violation(rule: "manifest", location: location, message: "依存 \(dependency.name) の Package を特定できません"))
                continue
            }
            if package != target.package && !allowed.contains(package) {
                violations.append(Violation(
                    rule: "package-dependency", location: location,
                    message: "\(target.package) は \(package) に依存できません。許可: \(allowed.sorted().joined(separator: ", "))"
                ))
            }
            if !target.isTest && dependency.name.hasSuffix("TestSupport") {
                violations.append(Violation(
                    rule: "test-support", location: location, message: "本番のターゲットが \(dependency.name) に依存しています"
                ))
            }
        }
    }
    return violations
}

private func ownPackage(of name: String, in ownTargets: [String: Set<String>], from package: String) -> String? {
    ownTargets[package]?.contains(name) == true ? package : nil
}
