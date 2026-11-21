import json
import shutil
import unittest
from pathlib import Path

from support import SCRIPTS, TemporaryDirectoryCase

import dca_package

PLUGIN_ROOT = SCRIPTS.parent


class PackageCheck(unittest.TestCase):
    def test_package_check_passes_on_repository(self):
        report = dca_package.all_problems(PLUGIN_ROOT)
        self.assertEqual({name: problems for name, problems in report.items() if problems}, {})

    def test_invocation_policy_matches_between_hosts(self):
        for name in dca_package.SKILLS:
            skill = PLUGIN_ROOT / "skills" / name
            fields, _ = dca_package.frontmatter((skill / "SKILL.md").read_text(encoding="utf-8"))
            implicit = dca_package.policy_value(skill / "agents" / "openai.yaml")
            manual = fields.get("disable-model-invocation") == "true"
            if name == "guide":
                self.assertTrue(implicit)
                self.assertFalse(manual)
            else:
                self.assertFalse(implicit, name)
                self.assertTrue(manual, name)

    def test_eight_skills_share_one_name_and_version_across_manifests(self):
        portable = json.loads((PLUGIN_ROOT / "plugin.json").read_text(encoding="utf-8"))
        claude = json.loads((PLUGIN_ROOT / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8"))
        marketplace = json.loads((PLUGIN_ROOT.parents[1] / ".claude-plugin" / "marketplace.json").read_text(encoding="utf-8"))
        entry = marketplace["plugins"][0]
        self.assertEqual({portable["name"], claude["name"], entry["name"]}, {"dca"})
        self.assertEqual({portable["version"], claude["version"], entry["version"]}, {portable["version"]})
        self.assertEqual(len(dca_package.SKILLS), 8)

    def test_portable_manifest_declares_its_schema(self):
        portable = json.loads((PLUGIN_ROOT / "plugin.json").read_text(encoding="utf-8"))
        self.assertTrue(portable["$schema"].startswith("https://agent-plugins.org/schemas/"))


class PackageMutation(TemporaryDirectoryCase):
    def setUp(self):
        super().setUp()
        self.plugin = self.root / "plugins" / "dca"
        shutil.copytree(PLUGIN_ROOT, self.plugin, ignore=shutil.ignore_patterns("__pycache__", "tests"))

    def edit(self, relative, old, new):
        path = self.plugin / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")

    def test_copy_passes_before_any_change(self):
        self.assertEqual(dca_package.skill_problems(self.plugin), [])
        self.assertEqual(dca_package.manifest_problems(self.plugin), [])

    def test_policy_mismatch_between_hosts_is_reported(self):
        self.edit("skills/map/agents/openai.yaml", "allow_implicit_invocation: false", "allow_implicit_invocation: true")
        self.assertTrue(any("map" in item and "起動方針" in item for item in dca_package.skill_problems(self.plugin)))

    def test_missing_required_read_is_reported(self):
        self.edit(
            "skills/refactor/SKILL.md",
            "- [検証](../principles/references/common/verification.md)\n",
            "",
        )
        self.assertTrue(any("verification.md" in item for item in dca_package.skill_problems(self.plugin)))

    def test_broken_relation_declaration_is_reported(self):
        self.edit("skills/map/SKILL.md", "- 引き継ぎ skill:scan:", "- 引き継ぎ skill:nothing:")
        self.assertTrue(any("対象の Skill が不正" in item for item in dca_package.skill_problems(self.plugin)))

    def test_reference_declared_in_relation_section_is_reported(self):
        self.edit("skills/map/SKILL.md", "- 引き継ぎ skill:scan:", "- 参照 skill:scan:")
        self.assertTrue(any("この節に書けない" in item for item in dca_package.skill_problems(self.plugin)))

    def test_changed_bundled_copy_is_reported(self):
        path = self.plugin / "skills" / "principles" / "references" / "dca" / "architecture.md"
        path.write_text(path.read_text(encoding="utf-8") + "\n追記\n", encoding="utf-8")
        self.assertTrue(any("SOURCES.json と違う" in item for item in dca_package.bundled_problems(self.plugin)))

    def test_changed_source_document_is_reported_when_the_source_repository_exists(self):
        docs = self.root / "docs"
        docs.mkdir()
        (docs / "architecture.md").write_text("元の文書を変えた\n", encoding="utf-8")
        self.assertTrue(any("追従していない" in item for item in dca_package.bundled_problems(self.plugin)))

    def test_missing_eval_case_is_reported(self):
        (self.plugin / "evals" / "cases" / "E05-interview-conflict.json").unlink()
        self.assertTrue(any("E05" in item for item in dca_package.eval_problems(self.plugin)))

    def test_deterministic_case_with_unknown_test_is_reported(self):
        path = self.plugin / "evals" / "cases" / "E15-overview-reproducible.json"
        case = json.loads(path.read_text(encoding="utf-8"))
        case["checks"] = ["test_overview.OverviewCheck.test_does_not_exist"]
        path.write_text(json.dumps(case, ensure_ascii=False), encoding="utf-8")
        shutil.copytree(PLUGIN_ROOT / "tests", self.plugin / "tests", ignore=shutil.ignore_patterns("__pycache__"))
        self.assertTrue(any("検査のテストがない" in item for item in dca_package.eval_problems(self.plugin)))

    def test_license_is_accepted_only_when_both_manifests_agree(self):
        portable_path = self.plugin / "plugin.json"
        portable = json.loads(portable_path.read_text(encoding="utf-8"))
        portable["license"] = "Apache-2.0"
        portable_path.write_text(json.dumps(portable), encoding="utf-8")
        self.assertTrue(any("license" in item for item in dca_package.manifest_problems(self.plugin)))
        claude_path = self.plugin / ".claude-plugin" / "plugin.json"
        claude = json.loads(claude_path.read_text(encoding="utf-8"))
        claude["license"] = "Apache-2.0"
        claude_path.write_text(json.dumps(claude), encoding="utf-8")
        self.assertFalse(any("license" in item for item in dca_package.manifest_problems(self.plugin)))

    def test_initial_package_rejects_hooks_and_mcp(self):
        (self.plugin / "hooks").mkdir()
        (self.plugin / "mcp.json").write_text("{}", encoding="utf-8")
        problems = dca_package.manifest_problems(self.plugin)
        self.assertTrue(any("hooks" in item for item in problems))
        self.assertTrue(any("mcp.json" in item for item in problems))

    def test_absolute_environment_path_is_reported(self):
        self.edit("skills/map/SKILL.md", "# map\n", "# map\n\n/Users/someone/work\n")
        self.assertTrue(any("/Users/" in item for item in dca_package.portability_problems(self.plugin)))

    def result_record(self, **overrides):
        case = json.loads((PLUGIN_ROOT / "evals" / "cases" / "E01-guide-route.json").read_text(encoding="utf-8"))
        record = {
            "case": "E01",
            "plugin_version": "0.1.0",
            "revision": "対象リポジトリの commit または内容の識別",
            "host": {"name": "codex-cli", "version": "0.171.0"},
            "distribution": "ローカルの marketplace",
            "conditions": ["認証なし", "モデルを呼ばない"],
            "fixture": case["fixture"],
            "expected": case["expected"],
            "observed": [],
            "evidence": [],
            "satisfied": [],
            "remaining": ["モデルの応答による判定"],
            "status": "実行不能",
            "reason": "",
        }
        record.update(overrides)
        path = self.root / "result.json"
        path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        return str(path)

    def test_result_record_requires_reason_for_non_success(self):
        self.assertTrue(any("reason" in item for item in dca_package.check_results(self.result_record(), self.plugin)))
        self.assertEqual(dca_package.check_results(self.result_record(reason="認証がない"), self.plugin), [])

    def test_successful_result_needs_observation_evidence_and_satisfied_conditions(self):
        problems = dca_package.check_results(self.result_record(status="成功"), self.plugin)
        for field in ("observed", "evidence", "satisfied"):
            self.assertTrue(any(field in item for item in problems), problems)
        complete = self.result_record(
            status="成功",
            observed=["guide が map を案内した"],
            evidence=["応答の本文の保存先"],
            satisfied=["Skill を一つ案内した"],
        )
        self.assertEqual(dca_package.check_results(complete, self.plugin), [])

    def test_result_must_match_the_case_definition(self):
        problems = dca_package.check_results(
            self.result_record(reason="x", fixture="kmp-shared", expected=["別の期待"]), self.plugin
        )
        self.assertTrue(any("fixture" in item for item in problems))
        self.assertTrue(any("expected" in item for item in problems))
        self.assertTrue(any("評価ケースがない" in item for item in dca_package.check_results(
            self.result_record(case="E99", reason="x"), self.plugin)))


if __name__ == "__main__":
    unittest.main()
