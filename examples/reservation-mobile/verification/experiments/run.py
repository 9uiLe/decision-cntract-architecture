#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.etree import ElementTree

EXPERIMENTS = Path(__file__).resolve().parent
MOBILE = EXPERIMENTS.parent.parent
KOTLIN_ERROR = re.compile(r"^e: .*\.kt:\d+:\d+.*$", re.MULTILINE)
FAILED_TASK = re.compile(r"^> Task (:[\w:-]+) FAILED$", re.MULTILINE)
BUILD_OUTPUTS = {"build", ".gradle", ".kotlin", ".git", "DcaApp.xcodeproj", "Info.plist", "local.properties", ".DS_Store"}
CHANGE_EXPERIMENTS = ("h", "i", "j", "k", "l")
COMPARISONS = ("c1", "c2", "c3")


def snapshot(root):
    files = {}
    for directory, paths, names in os.walk(root):
        paths[:] = [path for path in paths if path not in BUILD_OUTPUTS]
        for name in names:
            if name not in BUILD_OUTPUTS:
                path = Path(directory, name)
                files[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


def source_revision():
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=MOBILE,
        capture_output=True, text=True,
    )
    if revision.returncode:
        return {"commit": None, "productHasUncommittedChanges": None}
    status = subprocess.check_output(
        ["git", "status", "--porcelain", "--", "."], cwd=MOBILE, text=True,
    )
    return {"commit": revision.stdout.strip(), "productHasUncommittedChanges": bool(status)}


def verify_diagnostics(output, expected, work):
    actual = [line.replace(work.as_uri() + "/", "").replace(str(work) + "/", "")
              for line in KOTLIN_ERROR.findall(output)]
    for pattern in expected:
        matches = [line for line in actual if re.fullmatch(pattern, line)]
        if len(matches) != 1:
            raise ValueError(f"Expected one diagnostic matching {pattern!r}; observed {matches!r}")
        actual.remove(matches[0])
    if actual:
        raise ValueError(f"Unexpected Kotlin diagnostics: {actual}")


def verify_failure(work, step, output, evidence):
    if FAILED_TASK.findall(output) != [step["gradle"][0]]:
        raise ValueError(f"Expected only {step['gradle'][0]} to fail")

    if expected := step.get("diagnostics"):
        verify_diagnostics(output, expected, work)
    elif expected := step.get("detektViolation"):
        report = work / expected["report"]
        shutil.copyfile(report, evidence)
        violations = [
            [str((work / file.attrib["name"]).relative_to(work)), error.attrib["line"], error.attrib["source"]]
            for file in ElementTree.parse(report).getroot().findall("file")
            for error in file.findall("error")
        ]
        if violations != [[expected["file"], str(expected["line"]), expected["rule"]]]:
            raise ValueError(f"Unexpected detekt violations: {violations}")
    else:
        expected = step["unexpectedAcceptance"]
        report = work / "verification/compile-contracts/build/test-results/test/TEST-example.contracts.CompileContractsTest.xml"
        shutil.copyfile(report, evidence)
        failures = ElementTree.parse(report).getroot().findall(".//failure")
        if len(failures) != 1 or not all(part in failures[0].attrib["message"] for part in (
            expected, "expected: <COMPILATION_ERROR> but was: <OK>",
        )):
            raise ValueError(f"Expected only an unexpected successful compilation of {expected}")


def run(experiment, baseline, revision, evidence, gradle_options):
    definition = json.loads((experiment / "steps.json").read_text(encoding="utf-8"))
    evidence.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"dca-{experiment.name}-") as temporary:
        work = Path(temporary).resolve() / "reservation-mobile"
        shutil.copytree(baseline, work)
        before = snapshot(work)
        digest = hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest()
        print(f"{experiment.name}: baseline SHA-256 {digest}", flush=True)
        (evidence / "baseline.json").write_text(
            json.dumps({**revision, "sha256": digest}, indent=2) + "\n", encoding="utf-8",
        )

        for step in definition["steps"]:
            name = step["name"]
            for patch in step.get("patches", []):
                subprocess.run(
                    ["git", "apply", "--no-index", "--", str(experiment / patch)],
                    cwd=work, check=True,
                )
            task_arguments = step["gradle"]
            command = ["./gradlew", "--no-watch-fs", *gradle_options, *task_arguments]
            process = subprocess.run(
                command, cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
            )
            log = evidence / f"{name}.log"
            log.write_text(process.stdout, encoding="utf-8")
            expected = any(key in step for key in ("diagnostics", "detektViolation", "unexpectedAcceptance"))
            if expected:
                if process.returncode == 0:
                    raise ValueError(f"{name}: build succeeded, expected a failure ({log})")
                try:
                    verify_failure(work, step, process.stdout, evidence / f"{name}.xml")
                except ValueError as error:
                    raise ValueError(f"{name}: {error} ({log})") from error
            elif process.returncode:
                raise ValueError(f"{name}: Gradle exited {process.returncode} ({log})")
            if required := step.get("requiredTests"):
                verify_tests(work, required, evidence / f"{name}-tests.json")
            print(f"{name}: {'expected failure' if expected else 'passed'} ({log})", flush=True)

        after = snapshot(work)
        changed = {path for path in before.keys() | after.keys() if before.get(path) != after.get(path)}
        outside = sorted(path for path in changed if not any(
            path == allowed or path.startswith(allowed + "/") for allowed in definition["changes"]
        ))
        if outside:
            raise ValueError(f"Changes outside the experiment's declared scope: {outside}")
        (evidence / "changed-files.txt").write_text(
            "\n".join(sorted(changed)) + "\n", encoding="utf-8",
        )


def verify_tests(work, required, report):
    suites = {}
    results = (*work.glob("*/build/test-results/jvmTest/TEST-*.xml"), *work.glob("*/*/build/test-results/jvmTest/TEST-*.xml"))
    for path in sorted(results):
        module = path.parents[3].name
        suite = ElementTree.parse(path).getroot()
        tests = [case.attrib["name"] for case in suite.findall("testcase")]
        suites[f"{module}:{path.stem.removeprefix('TEST-')}"] = {
            "tests": tests,
            "failures": int(suite.attrib["failures"]) + int(suite.attrib["errors"]),
            "skipped": int(suite.attrib["skipped"]),
        }
    for test, minimum in required.items():
        suite = suites.get(test, {})
        if len(suite.get("tests", [])) < minimum or suite["failures"] or suite["skipped"]:
            raise ValueError(f"Expected at least {minimum} successful tests in {test}; observed {suite}")
    report.write_text(json.dumps(suites, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Verify an experiment against an isolated product copy.")
    parser.add_argument("experiment", choices=("all", "comparisons", *CHANGE_EXPERIMENTS, *COMPARISONS))
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--gradle-option", action="append", default=[])
    arguments = parser.parse_args()
    try:
        with tempfile.TemporaryDirectory(prefix="dca-baseline-") as temporary:
            revision = source_revision()
            baseline = Path(temporary) / "reservation-mobile"
            shutil.copytree(MOBILE, baseline, ignore=shutil.ignore_patterns("experiments", *BUILD_OUTPUTS))
            evidence = arguments.evidence.resolve()
            if arguments.experiment in ("all", "comparisons"):
                group = CHANGE_EXPERIMENTS if arguments.experiment == "all" else COMPARISONS
                for experiment in group:
                    run(
                        EXPERIMENTS / experiment, baseline, revision, evidence / experiment,
                        arguments.gradle_option,
                    )
            else:
                run(EXPERIMENTS / arguments.experiment, baseline, revision, evidence, arguments.gradle_option)
    except (OSError, ValueError, ElementTree.ParseError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
