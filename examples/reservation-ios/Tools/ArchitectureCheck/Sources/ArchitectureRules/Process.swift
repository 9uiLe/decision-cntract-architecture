import Foundation

public struct CommandResult: Sendable {
    public let status: Int32
    public let output: String
    public let errorOutput: String
}

public func run(_ executable: String, _ arguments: [String], directory: String? = nil) throws -> CommandResult {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable.hasPrefix("/") ? executable : "/usr/bin/env")
    process.arguments = executable.hasPrefix("/") ? arguments : [executable] + arguments
    if let directory { process.currentDirectoryURL = URL(fileURLWithPath: directory) }
    let standardOutput = Pipe()
    let standardError = Pipe()
    process.standardOutput = standardOutput
    process.standardError = standardError
    try process.run()
    let output = standardOutput.fileHandleForReading.readDataToEndOfFile()
    let errorOutput = standardError.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    return CommandResult(
        status: process.terminationStatus,
        output: String(bytes: output, encoding: .utf8) ?? "",
        errorOutput: String(bytes: errorOutput, encoding: .utf8) ?? ""
    )
}
