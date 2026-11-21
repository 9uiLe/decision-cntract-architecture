import json
import re
import shutil
import unittest
from html import unescape
from pathlib import Path

from support import SCRIPTS, TemporaryDirectoryCase

import dca_overview

PLUGIN_ROOT = SCRIPTS.parent


def stored_view():
    return json.loads((PLUGIN_ROOT / dca_overview.VIEW_PATH).read_text(encoding="utf-8"))


def stored_svg():
    return (PLUGIN_ROOT / dca_overview.SVG_PATH).read_text(encoding="utf-8")


def drawn_edges(svg):
    return {unescape(item) for item in re.findall(r'data-edge="([^"]*)"', svg)}


def drawn_nodes(svg):
    return {unescape(item) for item in re.findall(r'data-node="([^"]*)"', svg)}


class OverviewCheck(unittest.TestCase):
    def test_repository_view_and_svg_pass_the_check(self):
        self.assertEqual(dca_overview.check(PLUGIN_ROOT), [])

    def test_view_matches_registered_skills_and_inputs(self):
        view = stored_view()
        registered = dca_overview.registered_skills(PLUGIN_ROOT)
        self.assertEqual(len(registered), 8)
        self.assertEqual(view["registration"]["skills"], registered)
        self.assertEqual(sorted(node["label"] for node in view["nodes"] if node["kind"] == "skill"), registered)
        self.assertEqual([item["path"] for item in view["inputs"]], dca_overview.input_paths(PLUGIN_ROOT))

    def test_generated_outputs_are_not_inputs(self):
        paths = [item["path"] for item in stored_view()["inputs"]]
        self.assertFalse([path for path in paths if path.startswith("docs/") or "/results/" in path])
        self.assertFalse([path for path in paths if path.startswith("evals/")])

    def test_svg_draws_exactly_the_view_relations(self):
        self.assertEqual(dca_overview.drawn_problems(stored_view(), stored_svg()), [])

    def test_renderer_is_deterministic(self):
        view = stored_view()
        metrics = dca_overview.load_metrics(PLUGIN_ROOT)
        first = dca_overview.render(view, metrics)
        second = dca_overview.render(json.loads(json.dumps(view)), metrics)
        self.assertEqual(first, second)
        self.assertEqual(first, stored_svg())

    def test_svg_has_no_environment_dependent_values(self):
        svg = stored_svg()
        for fragment in ("/Users/", "/private/", "/home/", "file://"):
            self.assertNotIn(fragment, svg)
        self.assertIsNone(re.search(r"\d{4}-\d{2}-\d{2}", svg))

    def test_every_view_relation_has_evidence_inside_the_package(self):
        self.assertEqual(dca_overview.evidence_problems(PLUGIN_ROOT, stored_view()), [])

    def test_missing_glyph_in_metrics_is_an_error(self):
        metrics = dca_overview.load_metrics(PLUGIN_ROOT)
        with self.assertRaises(dca_overview.OverviewError):
            metrics.advance("\U0001F600")


class OverviewUpdate(TemporaryDirectoryCase):
    def setUp(self):
        super().setUp()
        self.plugin = self.root / "plugins" / "dca"
        shutil.copytree(PLUGIN_ROOT, self.plugin, ignore=shutil.ignore_patterns("__pycache__", "tests"))

    def guide_path(self):
        return self.plugin / "skills" / "guide" / "SKILL.md"

    def regenerate(self):
        view = dca_overview.extract(self.plugin)
        (self.plugin / dca_overview.VIEW_PATH).write_text(dca_overview.dump_view(view), encoding="utf-8")
        svg = dca_overview.render(view, dca_overview.load_metrics(self.plugin))
        (self.plugin / dca_overview.SVG_PATH).write_text(svg, encoding="utf-8")
        return view, svg

    def test_copy_passes_the_check_before_any_change(self):
        self.assertEqual(dca_overview.check(self.plugin), [])

    def test_stale_view_is_reported_after_a_declaration_changes(self):
        text = self.guide_path().read_text(encoding="utf-8")
        line = "- 案内 skill:lint: 機械で検査できるルールを提案してほしいとき\n"
        self.assertIn(line, text)
        self.guide_path().write_text(text.replace(line, ""), encoding="utf-8")
        problems = dca_overview.check(self.plugin)
        self.assertTrue(any("入力が変わったが view データが追従していない" in item for item in problems), problems)
        self.assertTrue(any("view データが、現在の入力から抽出した内容と一致しない" in item for item in problems), problems)

    def test_removing_a_guidance_declaration_removes_the_drawn_line(self):
        text = self.guide_path().read_text(encoding="utf-8")
        line = "- 案内 skill:lint: 機械で検査できるルールを提案してほしいとき\n"
        self.guide_path().write_text(text.replace(line, ""), encoding="utf-8")
        view, svg = self.regenerate()
        edge = "案内:skill:guide>skill:lint"
        self.assertNotIn(edge, [item["id"] for item in view["edges"]])
        self.assertNotIn(edge, drawn_edges(svg))
        self.assertEqual(dca_overview.check(self.plugin), [])
        self.assertIn("skill:lint", drawn_nodes(svg))

    def test_adding_a_skill_adds_a_box_and_a_line(self):
        extra = self.plugin / "skills" / "extra"
        (extra / "agents").mkdir(parents=True)
        (extra / "SKILL.md").write_text(
            "---\nname: extra\ndescription: 追加の Skill\ndisable-model-invocation: true\n---\n\n"
            "# extra\n\n追加した Skill の説明。\n\n"
            "## 読む資料\n\n"
            "- [情報モデル](../principles/references/common/information-model.md)\n\n"
            "## 他の Skill との関係\n\n"
            "- 生成 artifact:external-record: 追加の記録\n",
            encoding="utf-8",
        )
        (extra / "agents" / "openai.yaml").write_text(
            "policy:\n  allow_implicit_invocation: false\n", encoding="utf-8"
        )
        text = self.guide_path().read_text(encoding="utf-8")
        anchor = "- 案内 skill:overview: dca 自体の構成と、成果物の関係を確認したいとき\n"
        self.assertIn(anchor, text)
        self.guide_path().write_text(
            text.replace(anchor, anchor + "- 案内 skill:extra: 追加の目的のとき\n"), encoding="utf-8"
        )
        view, svg = self.regenerate()
        self.assertIn("extra", view["registration"]["skills"])
        self.assertIn("skill:extra", drawn_nodes(svg))
        self.assertIn("案内:skill:guide>skill:extra", drawn_edges(svg))
        self.assertEqual(dca_overview.check(self.plugin), [])

    def test_skill_without_guidance_still_appears_in_the_figure(self):
        extra = self.plugin / "skills" / "extra"
        (extra / "agents").mkdir(parents=True)
        (extra / "SKILL.md").write_text(
            "---\nname: extra\ndescription: 追加の Skill\ndisable-model-invocation: true\n---\n\n"
            "# extra\n\n案内されない Skill の説明。\n\n"
            "## 読む資料\n\n"
            "- [情報モデル](../principles/references/common/information-model.md)\n\n"
            "## 他の Skill との関係\n\n"
            "この Skill から他の Skill や成果物への関係は宣言しない。\n",
            encoding="utf-8",
        )
        (extra / "agents" / "openai.yaml").write_text(
            "policy:\n  allow_implicit_invocation: false\n", encoding="utf-8"
        )
        _, svg = self.regenerate()
        self.assertIn("skill:extra", drawn_nodes(svg))
        self.assertNotIn("案内:skill:guide>skill:extra", drawn_edges(svg))

    def test_second_guidance_hub_is_reported_as_unsupported(self):
        map_path = self.plugin / "skills" / "map" / "SKILL.md"
        text = map_path.read_text(encoding="utf-8")
        marker = "- 引き継ぎ skill:scan:"
        map_path.write_text(text.replace(marker, "- 案内 skill:lint: 別の起点からの案内\n" + marker), encoding="utf-8")
        view = dca_overview.extract(self.plugin)
        with self.assertRaises(dca_overview.OverviewError):
            dca_overview.render(view, dca_overview.load_metrics(self.plugin))

    def test_renamed_skill_directory_is_detected_by_registration(self):
        shutil.move(str(self.plugin / "skills" / "lint"), str(self.plugin / "skills" / "linting"))
        problems = dca_overview.check(self.plugin)
        self.assertTrue(problems)


if __name__ == "__main__":
    unittest.main()
