// 構築できるのは発行元の Package だけ。final class にし、init は internal に置く。
// 置き場所と構築口の制限は Tools/ArchitectureCheck と .swiftlint.yml が検査する。
public protocol IssuedEvidence: AnyObject, Sendable {}
