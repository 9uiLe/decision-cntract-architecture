import Foundation

public struct Checker {
    public let root: URL

    public init(root: URL) { self.root = root }

    private var packagesDirectory: URL { root.appendingPathComponent("Packages") }
    private var fixturesDirectory: URL { root.appendingPathComponent("Tools/ArchitectureCheck/Fixtures") }

    private func packageDirectories() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: packagesDirectory, includingPropertiesForKeys: nil)
            .filter { FileManager.default.fileExists(atPath: $0.appendingPathComponent("Package.swift").path) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func manifests() throws -> [(directory: URL, targets: [TargetManifest])] {
        try packageDirectories().map { directory in
            let result = try run("swift", ["package", "--package-path", directory.path, "dump-package"])
            guard result.status == 0 else { throw ManifestError.unreadable("\(directory.lastPathComponent): \(result.errorOutput)") }
            return (directory, try parseManifest(Data(result.output.utf8)))
        }
    }

    private func sources(_ directory: URL) -> [URL] {
        let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
        return (enumerator?.allObjects as? [URL] ?? []).filter { $0.pathExtension == "swift" }.sorted { $0.path < $1.path }
    }

    public func manifestAndImportViolations() throws -> [Violation] {
        let packages = try manifests()
        var violations = manifestViolations(packages.flatMap(\.targets))
        for (directory, targets) in packages {
            let ownModules = Set(targets.filter { !$0.isTest }.map(\.name))
            for target in targets {
                let targetDirectory = directory.appendingPathComponent(target.isTest ? "Tests" : "Sources").appendingPathComponent(target.name)
                for file in sources(targetDirectory) {
                    let source = try String(contentsOf: file, encoding: .utf8)
                    violations += importViolations(target: target, ownModules: ownModules, source: source, file: shortPath(file))
                }
            }
        }
        return violations
    }

    public func appViolations() throws -> [Violation] {
        let app = root.appendingPathComponent("App")
        var violations: [Violation] = []
        for file in sources(app.appendingPathComponent("ReservationIos")) {
            violations += appImportViolations(source: try String(contentsOf: file, encoding: .utf8), file: shortPath(file))
        }
        let project = app.appendingPathComponent("project.yml")
        violations += appPackageViolations(projectYAML: try String(contentsOf: project, encoding: .utf8), file: shortPath(project))
        return violations
    }

    public func evidenceViolations() throws -> [Violation] {
        var declarations: [EvidenceDeclaration] = []
        for directory in try packageDirectories() {
            for file in sources(directory.appendingPathComponent("Sources")) {
                declarations += evidenceDeclarations(source: try String(contentsOf: file, encoding: .utf8), file: shortPath(file))
            }
        }
        let names = compileFixtureNames(root: fixturesDirectory.appendingPathComponent("compile"))
        return ArchitectureRules.evidenceViolations(declarations, fixtureNames: names)
    }

    public func compileViolations() throws -> [Violation] {
        let composition = packagesDirectory.appendingPathComponent("Composition").path
        let build = try run("swift", ["build", "--package-path", composition])
        guard build.status == 0 else { return [Violation(rule: "build", location: "Composition", message: String(build.errorOutput.suffix(600)))] }
        let binPath = try run("swift", ["build", "--package-path", composition, "--show-bin-path"])
        let products = binPath.output.trimmingCharacters(in: .whitespacesAndNewlines)
        let modules = FileManager.default.fileExists(atPath: products + "/Modules") ? products + "/Modules" : products
        return try compileFixtureViolations(root: fixturesDirectory.appendingPathComponent("compile"), modulesDirectory: modules)
    }

    public func lintFixtureViolations(swiftlint: String) throws -> [Violation] {
        try ArchitectureRules.lintFixtureViolations(
            root: fixturesDirectory.appendingPathComponent("lint"), swiftlint: swiftlint,
            config: root.appendingPathComponent(".swiftlint.yml")
        )
    }

    private func shortPath(_ file: URL) -> String {
        let base = root.resolvingSymlinksInPath().path + "/"
        let resolved = file.resolvingSymlinksInPath().path
        return resolved.hasPrefix(base) ? String(resolved.dropFirst(base.count)) : resolved
    }
}
