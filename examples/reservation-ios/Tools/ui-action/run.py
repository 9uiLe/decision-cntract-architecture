#!/usr/bin/env python3
"""操作券・実行先の実クリック試験（XCUITest）の入口。

positive: UITests の全試験が通ること。product は製品の画面（ReservationStatusView と ActionButton）をそのまま使い、
          それ以外の variant は避けたい保持の仕方の再現で、失敗が画面経由で観測されることを確かめる。
negative: 製品コードの複製に退行を一つずつ入れ、product の試験だけが決まった数だけ失敗すること。
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IGNORED = {".build", ".tools", "build", ".swiftpm", "DerivedData", ".DS_Store", "ActionFixture.xcodeproj", "Info.plist"}
CASE = re.compile(r"Test Case '-\[[^ ]+ (test_[^\]]+)\]' (passed|failed)")
FAILURE = re.compile(r"error: -\[[^ ]+ (test_[^\]]+)\] : (.*)")

# 製品の画面をそのまま使う試験と、その失敗で最初に落ちるはずの観測欄。
PRODUCT_TESTS = {
    "test_同じ表示で券だけが替わっても_更新後の照会は新しい券で届く": "observe-lookups",
    "test_同じ結果不明の表示でも_再試行で替わった後の照会は新しい要求へ届く": "observe-lookups",
    "test_同じ券でも実行先が替わったら_更新後の照会は新しい実行先へ届く": "observe-notice-b",
}

# 避けたい保持の仕方を再現した負例。失敗が画面経由で観測されることを確かめる試験で、製品が正しければ通る。
NEGATIVE_TESTS = [
    "test_表示だけをキーにした保持は_券が替わった後の照会を拒否される",
    "test_表示の等価性で更新を落とす流れは_券が替わった後の照会を拒否される",
    "test_表示だけをキーにした保持は_新しい要求ではなく古い要求の券で拒否される",
    "test_券だけをキーにした保持は_実行先が替わった後も古い実行先へ届く",
]

BUTTON = "Packages/ReservationUI/Sources/ReservationUI/ActionButton.swift"

MUTATIONS = {
    "操作を最初の値のまま保持する": {
        "edits": [
            (BUTTON, "    let action: RequestAction\n", "    let action: RequestAction\n    @State private var held: RequestAction?\n", 1),
            (BUTTON, "Button(title) { action.execute() }", "Button(title) { (held ?? action).execute() }", 1),
            (BUTTON, "            .accessibilityIdentifier(identifier)\n", "            .accessibilityIdentifier(identifier)\n            .onAppear { if held == nil { held = action } }\n", 1),
        ],
        "fails": PRODUCT_TESTS,
    },
}


def run_tests(work, device, log, only=None):
    project = work / "UITests"
    subprocess.run(["xcodegen", "generate"], cwd=project, check=True, stdout=subprocess.DEVNULL)
    command = [
        "xcodebuild", "test", "-project", "ActionFixture.xcodeproj", "-scheme", "ActionFixture",
        "-destination", f"platform=iOS Simulator,id={device}", "-derivedDataPath", str(work / "DerivedData"),
        "-parallel-testing-enabled", "NO",
    ]
    if only:
        # 失敗を期待する実行では、失敗時の診断収集（スクリーンショットや端末ログ）が終わらないことがあるため止める。
        command += ["-collect-test-diagnostics", "never"]
    for name in only or []:
        command += ["-only-testing", f"ActionFixtureUITests/ActionClickUITests/{name}"]
    with log.open("w") as out:
        code = subprocess.run(command, cwd=project, stdout=out, stderr=subprocess.STDOUT).returncode
    text = log.read_text()
    outcomes = {name: result for name, result in CASE.findall(text)}
    failures = {}
    for name, message in FAILURE.findall(text):
        failures.setdefault(name, []).append(message)
    return code, outcomes, failures


def apply(work, edits):
    for relative, old, new, count in edits:
        path = work / relative
        text = path.read_text()
        if text.count(old) != count:
            raise SystemExit(f"{relative}: 変更前の記述が {count} 件ではありません: {old!r}")
        path.write_text(text.replace(old, new))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["positive", "negative"])
    parser.add_argument("--device", required=True, help="起動済みの専用 Simulator の UDID")
    parser.add_argument("--evidence", default=str(Path(tempfile.gettempdir()) / "dca-ui-click-evidence"))
    arguments = parser.parse_args()
    evidence = Path(arguments.evidence)
    evidence.mkdir(parents=True, exist_ok=True)

    if arguments.mode == "positive":
        code, outcomes, _ = run_tests(ROOT, arguments.device, evidence / "positive.log")
        failed = sorted(n for n, r in outcomes.items() if r == "failed")
        required = [*PRODUCT_TESTS, *NEGATIVE_TESTS]
        missing = [name for name in required if outcomes.get(name) != "passed"]
        ok = code == 0 and not failed and not missing
        if missing:
            print(f"通過を確認できない試験: {missing}")
        (evidence / "positive.json").write_text(json.dumps({"exit": code, "outcomes": outcomes}, ensure_ascii=False, indent=2))
        print(f"{'OK' if ok else 'NG'} positive: {len(outcomes)} 件実行、失敗 {len(failed)} 件 {failed}")
        sys.exit(0 if ok else 1)

    results = []
    with tempfile.TemporaryDirectory(prefix="dca-ui-click.") as base:
        for name, mutation in MUTATIONS.items():
            work = Path(base) / "work"
            if work.exists():
                shutil.rmtree(work)
            shutil.copytree(ROOT, work, ignore=shutil.ignore_patterns(*IGNORED))
            apply(work, mutation["edits"])
            code, outcomes, failures = run_tests(
                work, arguments.device, evidence / f"negative-{len(results)}.log", only=list(PRODUCT_TESTS)
            )
            failed = sorted(n for n, r in outcomes.items() if r == "failed")
            # 意図した観測（券や実行先が届かないこと）で落ちたことを、失敗メッセージで確かめる。環境の失敗を偽陽性にしない。
            intended = all(
                any(snippet in message for message in failures.get(test, []))
                for test, snippet in mutation["fails"].items()
            )
            ok = failed == sorted(mutation["fails"]) and code != 0 and intended
            results.append({"mutation": name, "failed": failed, "expected": list(mutation["fails"]), "intended": intended, "ok": ok})
            print(f"{'OK' if ok else 'NG'} {name}: 失敗 {len(failed)} 件 / 期待 {len(mutation['fails'])} 件、意図した観測 {intended}")
    (evidence / "negative.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    sys.exit(0 if all(r["ok"] for r in results) else 1)


if __name__ == "__main__":
    main()
