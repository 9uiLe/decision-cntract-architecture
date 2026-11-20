#!/bin/bash
set -eu
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

diag=(-Xrender-internal-diagnostic-names)

compile() { # module-name classpath output sources...
    local module="$1" cp="$2" jar="$3"
    shift 3
    kotlinc "${diag[@]}" -module-name "$module" ${cp:+-cp "$cp"} "$@" -d "$jar"
}

# 期待した診断だけで、期待した場所を理由に失敗したことを確かめる。
expect_rejected() { # source classpath expected-id
    local source="$1" cp="$2" id="$3"
    local base line log="$out/diagnostics.log"
    base="$(basename "$source")"
    line="$(grep -n 'VIOLATION\|DECISION-POINT' "$source" | head -1 | cut -d: -f1)"
    if kotlinc "${diag[@]}" -module-name dca-reject -cp "$cp" "$source" -d "$out/rejected.jar" > "$log" 2>&1; then
        echo "誤ってコンパイルできました: $base" >&2
        exit 1
    fi
    local errors matched
    errors="$(grep -c ': error: ' "$log" || true)"
    matched="$(grep -cE "(^|/)$base:$line:[0-9]+: error: \[$id\] " "$log" || true)"
    if [ "$errors" != "1" ] || [ "$matched" != "1" ]; then
        echo "期待した診断ではありません: $base (期待 $id, $base:$line)" >&2
        cat "$log" >&2
        exit 1
    fi
    echo "拒否を確認: $base -> [$id] at line $line"
}

compile dca-core "" "$out/core.jar" core/*.kt
compile dca-ui "$out/core.jar" "$out/ui.jar" ui/*.kt
compile dca-tests "$out/core.jar:$out/ui.jar" "$out/tests.jar" tests/*.kt
kotlin -cp "$out/core.jar:$out/ui.jar:$out/tests.jar" dca.tests.MainKt

for source in reject/*.kt; do
    expect_rejected "$source" "$out/core.jar:$out/ui.jar" \
        "$(sed -n 's|^// EXPECT: ||p' "$source")"
done

# Outcome に新しいケースを足した一時コピーで、Core は通り、結果を解釈する UI の射影だけが止まることを確かめる。
mkdir -p "$out/mutated/core" "$out/mutated/ui"
cp ui/*.kt "$out/mutated/ui/"
cp core/*.kt "$out/mutated/core/"
awk '{ print } /^sealed interface Outcome \{$/ { print "    data object ApprovalPending : Outcome" }' \
    core/Model.kt > "$out/mutated/core/Model.kt"
grep -q 'ApprovalPending' "$out/mutated/core/Model.kt"
compile dca-core "" "$out/mutated-core.jar" "$out"/mutated/core/*.kt
expect_rejected "$out/mutated/ui/Projection.kt" "$out/mutated-core.jar" NO_ELSE_IN_WHEN

echo "正常例の実行、独立した負例の拒否、Outcome 追加時の when の欠落検出を確認しました。"
