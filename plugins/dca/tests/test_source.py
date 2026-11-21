import json
import os
import unittest
from pathlib import Path
from unittest import mock

from support import TemporaryDirectoryCase, git, init_repository, run_state

import dca_source


class SourceIdentification(TemporaryDirectoryCase):
    def setUp(self):
        super().setUp()
        self.repo = init_repository(
            self.root / "repo",
            files={
                "Observed.kt": "val observed = 1\n",
                "Unrelated.kt": "val unrelated = 1\n",
                "tracked/Data.kt": "val first = 1\n",
            },
        )

    def identify(self, *paths):
        return dca_source.identify(str(self.repo), list(paths))

    def entry(self, document, path):
        return next(item for item in document["relevant"] if item["path"] == path)

    def test_states_for_tracked_untracked_ignored_and_missing_paths(self):
        (self.repo / ".gitignore").write_text("ignored.txt\n", encoding="utf-8")
        (self.repo / "new.txt").write_text("new\n", encoding="utf-8")
        (self.repo / "ignored.txt").write_text("ignored\n", encoding="utf-8")
        document = self.identify("Observed.kt", "new.txt", "ignored.txt", "gone.txt")
        states = {item["path"]: item["state"] for item in document["relevant"]}
        self.assertEqual(
            states,
            {"Observed.kt": "tracked", "new.txt": "untracked", "ignored.txt": "ignored", "gone.txt": "missing"},
        )
        self.assertIsNone(self.entry(document, "ignored.txt")["worktree_digest"])

    def test_sensitive_names_are_excluded_without_digest(self):
        (self.repo / ".env").write_text("TOKEN=secret-value\n", encoding="utf-8")
        document = self.identify(".env")
        item = self.entry(document, ".env")
        self.assertEqual(item["state"], "excluded_sensitive")
        self.assertIsNone(item["worktree_digest"])
        self.assertNotIn("secret-value", json.dumps(document))

    def test_identification_does_not_write_into_git_directory(self):
        (self.repo / "Observed.kt").write_text("val observed = 2\n", encoding="utf-8")
        before = self.snapshot(self.repo / ".git")
        self.identify("Observed.kt")
        self.assertEqual(before, self.snapshot(self.repo / ".git"))

    def snapshot(self, directory):
        return sorted(
            (str(path), path.stat().st_mtime_ns, path.stat().st_size)
            for path in directory.rglob("*")
            if path.is_file()
        )

    def test_staged_change_with_same_worktree_content_is_detected_outside_relevant(self):
        unrelated = self.repo / "Unrelated.kt"
        unrelated.write_text("val unrelated = 2\n", encoding="utf-8")
        git(self.repo, "add", "--", "Unrelated.kt")
        unrelated.write_text("val unrelated = 3\n", encoding="utf-8")
        before = self.identify("Observed.kt")
        git(self.repo, "add", "--", "Unrelated.kt")
        after = self.identify("Observed.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertIn("Unrelated.kt", result["outside_changes"])
        self.assertFalse(result["identical"])
        self.assertFalse(result["unchanged_within_coverage"])

    def test_change_to_relevant_path_is_reported(self):
        before = self.identify("Observed.kt")
        (self.repo / "Observed.kt").write_text("val observed = 5\n", encoding="utf-8")
        after = self.identify("Observed.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertEqual(result["relevant"], [{"path": "Observed.kt", "change": "changed"}])
        self.assertFalse(result["relevant_unchanged"])

    def test_unchanged_sources_are_identical(self):
        before = self.identify("Observed.kt")
        after = self.identify("Observed.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertTrue(result["identical"])
        self.assertEqual(result["unconfirmed_count"], 0)

    def test_tracked_path_behind_symlinked_parent_is_not_read(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "Data.kt").write_text("val external = 9\n", encoding="utf-8")
        (self.repo / "tracked" / "Data.kt").unlink()
        (self.repo / "tracked").rmdir()
        (self.repo / "tracked").symlink_to(outside, target_is_directory=True)
        before = self.identify("tracked/Data.kt")
        item = self.entry(before, "tracked/Data.kt")
        self.assertEqual(item["worktree_kind"], "unsafe_parent")
        self.assertIsNone(item["worktree_digest"])
        (outside / "Data.kt").write_text("val external = 10\n", encoding="utf-8")
        after = self.identify("tracked/Data.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertTrue(result["unchanged_within_coverage"])
        self.assertFalse(result["identical"])
        self.assertIn("unsafe_parent", [item["reason"] for item in result["unconfirmed"]])

    def test_symlinked_parent_in_dirty_set_is_not_read(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "Data.kt").write_text("val external = 9\n", encoding="utf-8")
        (self.repo / "tracked" / "Data.kt").unlink()
        (self.repo / "tracked").rmdir()
        (self.repo / "tracked").symlink_to(outside, target_is_directory=True)
        import hashlib

        external = hashlib.sha256(b"val external = 9\n").hexdigest()
        document = self.identify("Observed.kt")
        digests = [value["digest"] for value in document["dirty"]["entries"].values()]
        self.assertNotIn(external, digests)
        self.assertEqual(document["dirty"]["entries"]["tracked/Data.kt"]["digest"], None)
        self.assertEqual(document["dirty"]["entries"]["tracked/Data.kt"]["worktree_kind"], "unsafe_parent")

    def test_fifo_is_not_opened(self):
        os.mkfifo(str(self.repo / "pipe"))
        document = self.identify("pipe")
        item = self.entry(document, "pipe")
        self.assertEqual(item["state"], "outside_coverage")
        self.assertEqual(item["worktree_kind"], "non_regular")

    def test_tracked_file_replaced_by_fifo_is_not_opened(self):
        (self.repo / "Observed.kt").unlink()
        os.mkfifo(str(self.repo / "Observed.kt"))
        document = self.identify("Observed.kt", "Unrelated.kt")
        self.assertEqual(self.entry(document, "Observed.kt")["worktree_kind"], "non_regular")
        self.assertIsNone(self.entry(document, "Observed.kt")["worktree_digest"])
        self.assertEqual(document["dirty"]["entries"]["Observed.kt"]["worktree_kind"], "non_regular")
        result = dca_source.compare(document, self.identify("Observed.kt", "Unrelated.kt"), str(self.repo))
        self.assertFalse(result["identical"])
        self.assertIn("non_regular", [item["reason"] for item in result["unconfirmed"]])

    def test_truncated_dirty_set_is_never_identical_and_blocks_confirmed_verification(self):
        for number in range(5):
            (self.repo / ("extra%d.txt" % number)).write_text("x\n", encoding="utf-8")
        with mock.patch.object(dca_source, "MAX_DIRTY", 2):
            before = self.identify("Observed.kt")
            after = self.identify("Observed.kt")
        self.assertTrue(before["dirty"]["truncated"])
        result = dca_source.compare(before, after, str(self.repo))
        self.assertTrue(result["truncated"])
        self.assertFalse(result["identical"])
        self.assertIn("truncated", [item["reason"] for item in result["unconfirmed"]])
        environ = {"DCA_STATE_DIR": str(self.root / "state")}
        _, created = run_state(self.repo, "create", "--flow", "reservation", "--label", "予約", environ=environ)
        code, payload = run_state(
            self.repo,
            "verify",
            "--flow",
            "reservation",
            "--expected-token",
            created["token"],
            environ=environ,
            stdin=json.dumps(
                {
                    "guarantee": "g",
                    "target": "t",
                    "method": "m",
                    "status": "確認済み",
                    "identity_before": before,
                    "identity_after": after,
                }
            ),
        )
        self.assertEqual(code, 2)
        self.assertEqual(payload["reason"], "identity_truncated")

    def test_limited_identity_is_recorded_with_its_range(self):
        (self.repo / ".env").write_text("TOKEN=x\n", encoding="utf-8")
        before = self.identify("Observed.kt", ".env")
        after = self.identify("Observed.kt", ".env")
        environ = {"DCA_STATE_DIR": str(self.root / "state")}
        _, created = run_state(self.repo, "create", "--flow", "reservation", "--label", "予約", environ=environ)
        code, updated = run_state(
            self.repo,
            "verify",
            "--flow",
            "reservation",
            "--expected-token",
            created["token"],
            environ=environ,
            stdin=json.dumps(
                {
                    "guarantee": "g",
                    "target": "t",
                    "method": "m",
                    "status": "確認済み",
                    "identity_before": before,
                    "identity_after": after,
                }
            ),
        )
        self.assertEqual(code, 0, updated)
        _, read = run_state(self.repo, "read", "--flow", "reservation", environ=environ)
        checkout = read["record"]["checkouts"][read["target"]["checkout_key"]]
        stored = checkout["verifications"][0]
        self.assertEqual(stored["identity_scope"], "limited")
        self.assertIn("excluded_sensitive", [item["reason"] for item in stored["identity_limits"]])

    def test_pathspecs_are_literal(self):
        (self.repo / "[ab].txt").write_text("bracket\n", encoding="utf-8")
        (self.repo / "a.txt").write_text("a\n", encoding="utf-8")
        git(self.repo, "add", "-A")
        document = self.identify("[ab].txt")
        self.assertEqual([item["path"] for item in document["relevant"]], ["[ab].txt"])
        self.assertEqual(document["relevant"][0]["state"], "tracked")

    def test_rename_reports_both_old_and_new_paths(self):
        before = self.identify("Observed.kt")
        git(self.repo, "mv", "Unrelated.kt", "Renamed.kt")
        after = self.identify("Observed.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertIn("Unrelated.kt", result["outside_changes"])
        self.assertIn("Renamed.kt", result["outside_changes"])

    def test_non_utf8_name_is_recorded_without_reading_or_mismatching(self):
        raw_name = os.fsencode(str(self.repo / "bad")) + b"\xff"
        try:
            with open(raw_name, "wb") as handle:
                handle.write(b"x")
        except OSError:
            self.skipTest("このファイルシステムは UTF-8 でない名前を作れない")
        document = self.identify("Observed.kt")
        keys = list(document["dirty"]["entries"])
        self.assertTrue(any(key.startswith("unsupported_encoding:") for key in keys))
        before = document
        after = self.identify("Observed.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertFalse(result["identical"])
        self.assertIn("unsupported_encoding", [item["reason"] for item in result["unconfirmed"]])

    def test_compare_rejects_object_ids_that_are_not_hex(self):
        before = self.identify("Observed.kt")
        after = json.loads(json.dumps(before))
        after["checkout"]["head"] = "--output=/tmp/should-not-exist"
        with self.assertRaises(dca_source.SourceError):
            dca_source.compare(before, after, str(self.repo))

    def test_compare_rejects_paths_that_leave_the_repository(self):
        before = self.identify("Observed.kt")
        after = json.loads(json.dumps(before))
        after["relevant"][0]["path"] = "../escape"
        with self.assertRaises(dca_source.SourceError):
            dca_source.compare(before, after, str(self.repo))

    def test_head_change_lists_files_outside_relevant_paths(self):
        before = self.identify("Observed.kt")
        (self.repo / "Added.kt").write_text("val added = 1\n", encoding="utf-8")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "second")
        after = self.identify("Observed.kt")
        result = dca_source.compare(before, after, str(self.repo))
        self.assertTrue(result["head_changed"])
        self.assertIn("Added.kt", result["outside_changes"])
        self.assertFalse(result["head_diff_unavailable"])

    def test_paths_outside_repository_are_rejected(self):
        with self.assertRaises(dca_source.SourceError):
            self.identify("../elsewhere")
        with self.assertRaises(dca_source.SourceError):
            self.identify(str(self.root / "elsewhere"))


if __name__ == "__main__":
    unittest.main()
