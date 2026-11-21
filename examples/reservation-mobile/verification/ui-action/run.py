#!/usr/bin/env python3
"""予約状況の操作（照会・再試行）の負例を、製品コードの複製で実行する。

複製に実装の退行を一つずつ入れ、ActionClickTest・ViewModelStateClickTest・RequestActionTest の
決まったテストだけが失敗することを確かめる。元のツリーは書き換えない。
"""
import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

MOBILE = Path(__file__).resolve().parents[2]
BUILD_OUTPUTS = {"build", ".gradle", ".kotlin", ".git", "local.properties", ".DS_Store"}
UI = "ui/src/commonMain/kotlin/example/reservation/ui"
SCREEN = f"{UI}/ReservationStatusScreen.kt"

# ViewModel の状態の流れを表示の等価性で絞る変異は、CheckingStatus を観測した上流が券の変更を届けるため、
# 製品の変異としては検出できない。中間の表示を持たない流れでの失敗は、ActionClickTest の dedupeByDisplay で確かめる。
TICKET_CHANGES = "同じ表示で券だけが替わっても"
NEW_REQUEST = "同じ結果不明の表示でも"
NEW_ENDPOINT = "同じ券でも実行先が替わったら"
VIEW_MODEL_STATE = "ViewModel の状態の流れが"
ACTION_VALUES = "操作は実行先と券を保持"

HOLD = "    val modifier = Modifier.fillMaxWidth().testTag(tag)\n"
EXECUTE = "onClick = { action.execute() }"

MUTATIONS = {
    "操作を最初の値のまま保持する": {
        "edits": [
            (SCREEN, HOLD, HOLD + "    val held = androidx.compose.runtime.remember { action }\n", 1),
            (SCREEN, EXECUTE, "onClick = { held.execute() }", 2),
        ],
        "fails": [TICKET_CHANGES, NEW_REQUEST, NEW_ENDPOINT, VIEW_MODEL_STATE],
    },
    "操作を実行先だけをキーに保持する（券を落とす）": {
        "edits": [
            (SCREEN, HOLD, HOLD + "    val held = androidx.compose.runtime.remember(action.endpoint) { action }\n", 1),
            (SCREEN, EXECUTE, "onClick = { held.execute() }", 2),
        ],
        "fails": [TICKET_CHANGES, NEW_REQUEST, VIEW_MODEL_STATE],
    },
    "操作の等価性が券を含まない": {
        "edits": [
            (
                f"{UI}/RequestAction.kt",
                "        override fun execute(): Acceptance = endpoint.checkStatus(ticket)\n",
                "        override fun execute(): Acceptance = endpoint.checkStatus(ticket)\n\n"
                "        override fun equals(other: Any?): Boolean = other is CheckStatus && other.endpoint === endpoint\n\n"
                "        override fun hashCode(): Int = endpoint.hashCode()\n",
                1,
            ),
        ],
        "fails": [ACTION_VALUES, TICKET_CHANGES, NEW_REQUEST, VIEW_MODEL_STATE],
    },
    "カードを券ごとの key で作り直す": {
        "edits": [
            (
                SCREEN,
                "state.request?.let { RequestCardContent(it, endpoint, onChooseAgain) }",
                "state.request?.let { androidx.compose.runtime.key(it) { RequestCardContent(it, endpoint, onChooseAgain) } }",
                1,
            ),
        ],
        "fails": [TICKET_CHANGES],
    },
}


def apply(work, edits):
    for relative, old, new, count in edits:
        path = work / relative
        text = path.read_text()
        if text.count(old) != count:
            raise SystemExit(f"{relative}: 変更前の記述が {count} 件ではありません: {old!r}")
        path.write_text(text.replace(old, new))


def failed_tests(work):
    failed = []
    for report in (work / "ui/build/test-results/jvmTest").glob("TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(case.get("name"))
    return sorted(failed)


def run_gradle(work, gradle_options, log):
    command = ["./gradlew", "--no-watch-fs", *gradle_options, ":ui:jvmTest"]
    with log.open("w") as out:
        return subprocess.run(command, cwd=work, stdout=out, stderr=subprocess.STDOUT).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--gradle-option", action="append", default=[])
    parser.add_argument("--only", action="append", default=[], help="実行する変異の名前（既定はすべて）")
    arguments = parser.parse_args()
    evidence = Path(arguments.evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    selected = arguments.only or list(MUTATIONS)
    results = []
    with tempfile.TemporaryDirectory(prefix="dca-ui-action.") as root:
        baseline = Path(root) / "baseline"
        shutil.copytree(MOBILE, baseline, ignore=shutil.ignore_patterns(*BUILD_OUTPUTS))
        for name in ["変更なし", *selected]:
            work = Path(root) / "work"
            if work.exists():
                shutil.rmtree(work)
            shutil.copytree(baseline, work)
            expected = [] if name == "変更なし" else MUTATIONS[name]["fails"]
            if name != "変更なし":
                apply(work, MUTATIONS[name]["edits"])
            code = run_gradle(work, arguments.gradle_option, evidence / f"{len(results):02d}.log")
            failed = failed_tests(work)
            matched = all(any(prefix in test for test in failed) for prefix in expected) and len(failed) == len(expected)
            ok = matched and (code == 0 if not expected else code != 0)
            results.append({"mutation": name, "gradleExit": code, "expectedFailures": expected, "failedTests": failed, "ok": ok})
            print(f"{'OK ' if ok else 'NG '}{name}: 失敗したテスト {len(failed)} 件", flush=True)
    (evidence / "result.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    sys.exit(0 if all(r["ok"] for r in results) else 1)


if __name__ == "__main__":
    main()
