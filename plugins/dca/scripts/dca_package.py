import argparse
import hashlib
import json
import os
import py_compile
import re
import sys
import tempfile
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parents[1]
SKILLS = ("guide", "overview", "map", "scan", "interview", "refactor", "lint", "principles")
IMPLICIT_ALLOWED = ("guide",)
REFERENCES = PLUGIN_ROOT / "skills" / "principles" / "references"
COMMON = REFERENCES / "common"
BUNDLED_SOURCES = (
    ("architecture.md", "docs/architecture.md"),
    ("verification.md", "docs/verification.md"),
    ("detekt-rules.md", "docs/detekt-rules.md"),
    ("experiments/contract-comparisons.md", "docs/experiments/contract-comparisons.md"),
    ("experiments/i-check-in-qualification.md", "docs/experiments/i-check-in-qualification.md"),
    ("mobile-app/contract-evidence.md", "docs/mobile-app/contract-evidence.md"),
)
REQUIRED_READS = {
    "map": ("information-model.md", "record-storage.md"),
    "scan": ("information-model.md", "record-storage.md", "verification.md"),
    "interview": ("information-model.md", "record-storage.md", "migration-workflow.md"),
    "refactor": ("information-model.md", "record-storage.md", "migration-workflow.md", "verification.md"),
    "lint": ("information-model.md", "verification.md"),
    "guide": ("record-storage.md",),
}
ARTIFACTS = ("external-record", "overview-svg", "overview-view")
RELATION = re.compile(r"^- (案内|参照|呼び出し|引き継ぎ|生成|実行環境) (skill|artifact):([a-z-]+): (.+)$")
DECLARABLE_KINDS = ("案内", "呼び出し", "引き継ぎ", "生成")
LINK = re.compile(r"\]\(([^)\s]+)\)")
FORBIDDEN_FRAGMENTS = ("/Users/", "/private/", "/home/", "C:\\")
FORBIDDEN_PATHS = ("hooks", ".mcp.json", "mcp.json", ".app.json", ".codex-plugin")
EVAL_IDS = tuple("E%02d" % number for number in range(1, 20))
RESULT_STATUSES = ("成功", "失敗", "実行不能", "未実施")


def read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def file_digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def frontmatter(text):
    lines = text.split("\n")
    if not lines or lines[0] != "---":
        return None, text
    try:
        end = lines.index("---", 1)
    except ValueError:
        return None, text
    fields = {}
    for line in lines[1:end]:
        key, separator, value = line.partition(": ")
        if separator:
            fields[key] = value
    return fields, "\n".join(lines[end + 1:])


def sections(body):
    found = {}
    current = None
    for line in body.split("\n"):
        if line.startswith("## "):
            current = line[3:].strip()
            found[current] = []
        elif current is not None:
            found[current].append(line)
    return found


def read_links(lines):
    return [match.group(1) for line in lines if line.startswith("- [") for match in LINK.finditer(line)]


def manifest_problems(root):
    problems = []
    portable = read_json(root / "plugin.json")
    claude = read_json(root / ".claude-plugin" / "plugin.json")
    if not str(portable.get("$schema", "")).startswith("https://agent-plugins.org/schemas/"):
        problems.append("plugin.json に Agent Plugins の $schema が必要")
    if portable.get("name") != "dca" or claude.get("name") != "dca":
        problems.append("Plugin 名は dca にする")
    if portable.get("version") != claude.get("version"):
        problems.append("plugin.json と .claude-plugin/plugin.json の version が違う")
    if portable.get("license") != claude.get("license"):
        problems.append("plugin.json と .claude-plugin/plugin.json の license が違う")
    marketplace_path = root.parents[1] / ".claude-plugin" / "marketplace.json"
    if marketplace_path.is_file():
        marketplace = read_json(marketplace_path)
        entries = [entry for entry in marketplace.get("plugins", []) if entry.get("name") == "dca"]
        if len(entries) != 1:
            problems.append("marketplace.json に dca の entry が一つ必要")
        else:
            entry = entries[0]
            if entry.get("version") != portable.get("version"):
                problems.append("marketplace.json の version が plugin.json と違う")
            if entry.get("source") != "./plugins/dca":
                problems.append("marketplace.json の source は ./plugins/dca にする")
    for name in FORBIDDEN_PATHS:
        if (root / name).exists():
            problems.append("初期版に含めないもの: %s" % name)
    for key in ("hooks", "mcpServers", "apps"):
        if key in claude:
            problems.append(".claude-plugin/plugin.json に %s は置かない" % key)
    extension = portable.get("extensions", {}).get("com.openai", {})
    for key in ("hooks", "apps"):
        if key in extension:
            problems.append("extensions.com.openai に %s は置かない" % key)
    return problems


def policy_value(path):
    match = re.search(r"^\s+allow_implicit_invocation:\s*(true|false)\s*$", path.read_text(encoding="utf-8"), re.M)
    return None if match is None else match.group(1) == "true"


def skill_problems(root):
    problems = []
    skills_dir = root / "skills"
    registered = tuple(sorted(item.name for item in skills_dir.iterdir() if (item / "SKILL.md").is_file()))
    if registered != tuple(sorted(SKILLS)):
        problems.append("Skill の登録集合が 8 件と一致しない: %s" % ", ".join(registered))
    for name in SKILLS:
        skill_path = skills_dir / name / "SKILL.md"
        if not skill_path.is_file():
            continue
        fields, body = frontmatter(skill_path.read_text(encoding="utf-8"))
        if fields is None:
            problems.append("%s: frontmatter がない" % name)
            continue
        if fields.get("name") != name:
            problems.append("%s: frontmatter の name がディレクトリ名と違う" % name)
        if not fields.get("description"):
            problems.append("%s: description がない" % name)
        unknown = set(fields) - {"name", "description", "disable-model-invocation"}
        if unknown:
            problems.append("%s: 想定外の frontmatter: %s" % (name, ", ".join(sorted(unknown))))
        manual = fields.get("disable-model-invocation") == "true"
        metadata = skills_dir / name / "agents" / "openai.yaml"
        implicit = policy_value(metadata) if metadata.is_file() else None
        if implicit is None:
            problems.append("%s: agents/openai.yaml の policy.allow_implicit_invocation がない" % name)
        expected_implicit = name in IMPLICIT_ALLOWED
        if manual == expected_implicit:
            problems.append("%s: disable-model-invocation の指定が起動方針と合わない" % name)
        if implicit is not None and implicit != expected_implicit:
            problems.append("%s: openai.yaml の allow_implicit_invocation が起動方針と合わない" % name)
        parts = sections(body)
        for heading in ("読む資料", "他の Skill との関係"):
            if heading not in parts:
                problems.append("%s: 「%s」の節がない" % (name, heading))
        problems.extend(read_problems(root, name, skill_path, parts))
        problems.extend(relation_problems(name, parts))
    return problems


def read_problems(root, name, skill_path, parts):
    problems = []
    links = read_links(parts.get("読む資料", []))
    resolved = []
    for link in links:
        target = (skill_path.parent / link.split("#")[0]).resolve()
        if not target.is_file():
            problems.append("%s: 読む資料のリンク先がない: %s" % (name, link))
        resolved.append(target)
    common = root / "skills" / "principles" / "references" / "common"
    for needed in REQUIRED_READS.get(name, ()):
        if (common / needed).resolve() not in resolved:
            problems.append("%s: 読む資料に %s が必要" % (name, needed))
    return problems


def relation_problems(name, parts):
    problems = []
    for line in parts.get("他の Skill との関係", []):
        if not line.startswith("- "):
            continue
        match = RELATION.match(line)
        if match is None:
            problems.append("%s: 関係の宣言が定型に合わない: %s" % (name, line))
            continue
        kind, target_kind, target = match.group(1), match.group(2), match.group(3)
        if kind not in DECLARABLE_KINDS:
            problems.append("%s: この節に書けない関係の種類: %s" % (name, kind))
        if target_kind == "skill" and (target not in SKILLS or target == name):
            problems.append("%s: 関係の対象の Skill が不正: %s" % (name, target))
        if target_kind == "artifact" and target not in ARTIFACTS:
            problems.append("%s: 関係の対象の成果物が不正: %s" % (name, target))
        if kind == "生成" and target_kind != "artifact":
            problems.append("%s: 生成の対象は成果物にする" % name)
        if kind in ("案内", "引き継ぎ", "呼び出し") and target_kind != "skill":
            problems.append("%s: %s の対象は Skill にする" % (name, kind))
    return problems


def text_files(root):
    for path in sorted(root.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts and path.suffix in (".md", ".json", ".yaml", ".py", ".yml"):
            yield path


def link_problems(root):
    problems = []
    bundled = root / "skills" / "principles" / "references" / "dca"
    for path in text_files(root):
        if path.suffix != ".md" or bundled in path.parents or "fixtures" in path.parts:
            continue
        for link in LINK.findall(path.read_text(encoding="utf-8")):
            if link.startswith(("http://", "https://", "#")):
                continue
            target = (path.parent / link.split("#")[0]).resolve()
            if not target.exists():
                problems.append("%s: リンク先がない: %s" % (path.relative_to(root), link))
            elif root.resolve() not in target.parents and target != root.resolve():
                problems.append("%s: Plugin の外を指すリンク: %s" % (path.relative_to(root), link))
    return problems


def portability_problems(root):
    problems = []
    for path in text_files(root):
        if "tests" in path.parts or "fixtures" in path.parts or path.name == "dca_package.py":
            continue
        text = path.read_text(encoding="utf-8")
        for fragment in FORBIDDEN_FRAGMENTS:
            if fragment in text:
                problems.append("%s: 環境依存の絶対パスを含む: %s" % (path.relative_to(root), fragment))
    for path in sorted((root / "scripts").glob("*.py")):
        try:
            with tempfile.TemporaryDirectory() as scratch:
                py_compile.compile(str(path), cfile=os.path.join(scratch, "compiled.pyc"), doraise=True)
        except py_compile.PyCompileError as error:
            problems.append("%s: 構文エラー: %s" % (path.name, error.msg))
    return problems


def bundled_problems(root):
    problems = []
    bundled = root / "skills" / "principles" / "references" / "dca"
    sources_path = bundled / "SOURCES.json"
    if not sources_path.is_file():
        return ["同梱思想の対応表 dca/SOURCES.json がない"]
    sources = read_json(sources_path)
    recorded = {item["copy"]: item for item in sources.get("files", [])}
    if set(recorded) != {copy for copy, _ in BUNDLED_SOURCES}:
        problems.append("SOURCES.json のファイル集合が同梱予定と違う")
    index_text = (root / "skills" / "principles" / "references" / "index.md").read_text(encoding="utf-8")
    source_repo = root.parents[1]
    for copy, source in BUNDLED_SOURCES:
        item = recorded.get(copy)
        path = bundled / copy
        if item is None or not path.is_file():
            problems.append("同梱した思想がない: %s" % copy)
            continue
        if file_digest(path) != item["sha256"]:
            problems.append("同梱した思想の内容が SOURCES.json と違う: %s" % copy)
        origin = source_repo / source
        if origin.is_file() and file_digest(origin) != item["sha256"]:
            problems.append("元の文書が変わったが、同梱した思想が追従していない: %s" % source)
        if "dca/" + copy not in index_text:
            problems.append("索引に同梱した思想の行がない: dca/%s" % copy)
    return problems


def sync_references(root):
    source_repo = root.parents[1]
    bundled = root / "skills" / "principles" / "references" / "dca"
    files = []
    for copy, source in BUNDLED_SOURCES:
        origin = source_repo / source
        if not origin.is_file():
            raise SystemExit("複写元がない: %s" % origin)
        target = bundled / copy
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(origin.read_bytes())
        files.append({"copy": copy, "source": source, "sha256": file_digest(target)})
    manifest = {"schema": 1, "source_repository": "9uiLe/decision-cntract-architecture", "files": files}
    (bundled / "SOURCES.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return [item["copy"] for item in files]


def eval_problems(root):
    problems = []
    cases_dir = root / "evals" / "cases"
    if not cases_dir.is_dir():
        return ["evals/cases がない"]
    found = {}
    for path in sorted(cases_dir.glob("*.json")):
        case = read_json(path)
        found[case.get("id")] = case
        for field in ("id", "viewpoint", "skills", "hosts", "automation", "setup", "prompt", "expected", "evidence"):
            if field not in case:
                problems.append("%s: 必須の項目 %s がない" % (path.name, field))
        if not path.name.startswith(str(case.get("id"))):
            problems.append("%s: ファイル名が id と合わない" % path.name)
        fixture = case.get("fixture")
        if fixture and not (root / "evals" / "fixtures" / fixture).is_dir():
            problems.append("%s: fixture がない: %s" % (path.name, fixture))
        for skill in case.get("skills", []):
            if skill not in SKILLS:
                problems.append("%s: 不明な Skill: %s" % (path.name, skill))
        if case.get("automation") == "deterministic":
            for check in case.get("checks", []):
                if not has_test(root, check):
                    problems.append("%s: 検査のテストがない: %s" % (path.name, check))
            if not case.get("checks"):
                problems.append("%s: deterministic のケースに checks が必要" % path.name)
    missing = [identifier for identifier in EVAL_IDS if identifier not in found]
    if missing:
        problems.append("評価ケースが足りない: %s" % ", ".join(missing))
    covered = {skill for case in found.values() for skill in case.get("skills", [])}
    for skill in SKILLS:
        if skill not in covered:
            problems.append("評価ケースに含まれない Skill: %s" % skill)
    return problems


def has_test(root, dotted):
    module, _, name = dotted.partition(".")
    path = root / "tests" / ("%s.py" % module)
    if not path.is_file():
        return False
    return ("def %s(" % name.rsplit(".", 1)[-1]) in path.read_text(encoding="utf-8")


RESULT_TEXT_FIELDS = ("case", "plugin_version", "revision", "distribution", "status")
RESULT_LIST_FIELDS = ("conditions", "expected", "observed", "evidence", "satisfied", "remaining")


def check_results(path, root=PLUGIN_ROOT):
    problems = []
    result = read_json(path)
    for field in RESULT_TEXT_FIELDS:
        if not isinstance(result.get(field), str) or not result[field]:
            problems.append("結果に %s がない" % field)
    for field in RESULT_LIST_FIELDS:
        if not isinstance(result.get(field), list):
            problems.append("結果の %s は配列にする" % field)
    host = result.get("host")
    if not isinstance(host, dict) or not host.get("name") or not host.get("version"):
        problems.append("結果の host に name と version が要る")
    if "fixture" not in result:
        problems.append("結果に fixture がない。使わないときは null にする")
    status = result.get("status")
    if status not in RESULT_STATUSES:
        problems.append("status は %s のどれかにする" % "、".join(RESULT_STATUSES))
    if status in ("失敗", "実行不能", "未実施") and not result.get("reason"):
        problems.append("失敗、実行不能、未実施には reason が要る")
    if status == "成功":
        for field in ("conditions", "observed", "evidence", "satisfied"):
            if not result.get(field):
                problems.append("成功の結果には %s が要る" % field)
    case_path = next(iter((root / "evals" / "cases").glob("%s-*.json" % result.get("case", ""))), None)
    if case_path is None:
        problems.append("評価ケースがない: %s" % result.get("case"))
    else:
        case = read_json(case_path)
        if result.get("fixture") != case.get("fixture"):
            problems.append("結果の fixture が、評価ケースの定義と違う")
        if result.get("expected") != case.get("expected"):
            problems.append("結果の expected が、評価ケースの定義と違う")
    return problems


def overview_problems(root):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import dca_overview

    return dca_overview.check(root)


def all_problems(root):
    checks = (
        ("manifest", manifest_problems),
        ("skills", skill_problems),
        ("links", link_problems),
        ("portability", portability_problems),
        ("bundled", bundled_problems),
        ("evals", eval_problems),
        ("overview", overview_problems),
    )
    return {name: check(root) for name, check in checks}


def main(argv=None):
    parser = argparse.ArgumentParser(prog="dca_package")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check")
    commands.add_parser("sync-references")
    results = commands.add_parser("check-result")
    results.add_argument("path")
    arguments = parser.parse_args(argv)
    if arguments.command == "sync-references":
        print(json.dumps({"synced": sync_references(PLUGIN_ROOT)}, ensure_ascii=False))
        return 0
    if arguments.command == "check-result":
        problems = check_results(arguments.path)
        print(json.dumps({"problems": problems}, ensure_ascii=False))
        return 1 if problems else 0
    report = all_problems(PLUGIN_ROOT)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if any(report.values()) else 0


if __name__ == "__main__":
    sys.exit(main())
