#!/bin/sh
# 検査用の SwiftLint を、版とハッシュを固定して .tools/ へ取得する。取得したファイルは SHA-256 が一致しなければ使わない。
set -eu

version="0.65.1"
sha256="c1e429b0599cf1b516f369a2d9ec04eaf0e436f3c12b637df8851fa52ff694d0"
url="https://github.com/realm/SwiftLint/releases/download/${version}/portable_swiftlint.zip"

root=$(cd "$(dirname "$0")/.." && pwd)
destination="$root/.tools"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$destination"
curl --fail --location --silent --show-error --output "$work/swiftlint.zip" "$url"
actual=$(shasum -a 256 "$work/swiftlint.zip" | cut -d ' ' -f 1)
if [ "$actual" != "$sha256" ]; then
    echo "SHA-256 が一致しません。期待: $sha256 実際: $actual" >&2
    exit 1
fi
unzip -q -o "$work/swiftlint.zip" swiftlint -d "$destination"
chmod +x "$destination/swiftlint"
"$destination/swiftlint" version
