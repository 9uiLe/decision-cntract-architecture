// 比較は同一性。表示が同じでも、券か実行先が違えば別の値になる。クラスを Equatable にせず、この型だけが === を使う。
public struct ByIdentity<Object: AnyObject & Sendable>: Equatable, Sendable {
    public let object: Object

    public init(_ object: Object) { self.object = object }

    public static func == (lhs: Self, rhs: Self) -> Bool { lhs.object === rhs.object }
}
