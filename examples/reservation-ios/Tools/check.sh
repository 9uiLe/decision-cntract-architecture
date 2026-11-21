#!/bin/sh
# 標準の検査。SwiftLint、境界検査、各 Package のテストを順に実行する。
# --with-app を付けると、XcodeGen で生成したアプリを iOS Simulator 向けにビルドする（機器は使わない）。
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
swiftlint="${SWIFTLINT:-$root/.tools/swiftlint}"

if [ ! -x "$swiftlint" ]; then
    echo "SwiftLint が見つかりません: $swiftlint" >&2
    echo "sh Tools/bootstrap-swiftlint.sh で取得するか、環境変数 SWIFTLINT に実行ファイルを指定してください。" >&2
    exit 2
fi

echo "== SwiftLint"
"$swiftlint" lint --strict --quiet --no-cache Packages App Tools/ArchitectureCheck/Sources Tools/ArchitectureCheck/Tests

echo "== 境界検査（manifest・import・発行物・コンパイル・SwiftLint の負例）"
swift run --package-path Tools/ArchitectureCheck architecture-check all --root "$root" --swiftlint "$swiftlint"

echo "== swift test"
for package in Packages/* Tools/ArchitectureCheck; do
    echo "-- $package"
    swift test --package-path "$package"
done

if [ "${1:-}" = "--with-app" ]; then
    echo "== アプリのビルド（iOS Simulator 向け）"
    (cd App && xcodegen generate && xcodebuild -project ReservationIos.xcodeproj -scheme ReservationIos \
        -destination 'generic/platform=iOS Simulator' -derivedDataPath build build)
fi
