import hashlib
import json
import os
import shutil
import unittest
from pathlib import Path

from support import SCRIPTS, TemporaryDirectoryCase, git, init_repository, run_state, spawn_state

import dca_source
import dca_state


class StateDirectorySelection(TemporaryDirectoryCase):
    def test_explicit_value_wins_over_xdg_and_default(self):
        repo = init_repository(self.root / "repo")
        code, payload = run_state(
            repo,
            "where",
            environ={"DCA_STATE_DIR": str(self.state), "XDG_STATE_HOME": str(self.root / "xdg")},
        )
        self.assertEqual(code, 0)
        self.assertEqual(payload["location"]["state_dir"], str(self.state))
        self.assertEqual(payload["location"]["selected_by"], "DCA_STATE_DIR")

    def test_xdg_state_home_gets_dca_suffix(self):
        repo = init_repository(self.root / "repo")
        code, payload = run_state(repo, "where", environ={"XDG_STATE_HOME": str(self.root / "xdg")})
        self.assertEqual(code, 0)
        self.assertEqual(payload["location"]["state_dir"], str(self.root / "xdg" / "dca"))
        self.assertEqual(payload["location"]["selected_by"], "XDG_STATE_HOME")

    def test_default_is_under_home_local_state(self):
        repo = init_repository(self.root / "repo")
        code, payload = run_state(repo, "where", environ={"HOME": str(self.root / "home")})
        self.assertEqual(code, 0)
        self.assertEqual(payload["location"]["state_dir"], str(self.root / "home" / ".local" / "state" / "dca"))
        self.assertEqual(payload["location"]["selected_by"], "default")

    def test_relative_explicit_value_is_refused(self):
        repo = init_repository(self.root / "repo")
        for variable in ("DCA_STATE_DIR", "XDG_STATE_HOME"):
            code, payload = run_state(repo, "where", environ={variable: "relative/path"})
            self.assertEqual(code, 2)
            self.assertEqual(payload["reason"], "state_dir_not_absolute")


class StateDirectoryRejection(TemporaryDirectoryCase):
    def refused(self, repo, state_dir):
        code, payload = run_state(repo, "where", environ={"DCA_STATE_DIR": str(state_dir)})
        self.assertEqual(code, 2, payload)
        self.assertEqual(payload["status"], "refused")
        return payload["reason"]

    def test_inside_target_repository(self):
        repo = init_repository(self.root / "repo")
        self.assertEqual(self.refused(repo, repo / "docs" / "state"), "state_dir_inside_target_repository")

    def test_equal_to_target_repository(self):
        repo = init_repository(self.root / "repo")
        self.assertEqual(self.refused(repo, repo), "state_dir_inside_target_repository")

    def test_inside_git_directory(self):
        repo = init_repository(self.root / "repo")
        self.assertIn(self.refused(repo, repo / ".git" / "dca"), ("state_dir_inside_git_dir", "state_dir_inside_target_repository"))

    def test_symlink_that_resolves_into_repository(self):
        repo = init_repository(self.root / "repo")
        link = self.root / "link"
        link.symlink_to(repo)
        self.assertEqual(self.refused(repo, link / "state"), "state_dir_inside_target_repository")

    def test_inside_other_worktree_of_same_project(self):
        repo = init_repository(self.root / "repo")
        other = self.root / "other"
        git(repo, "worktree", "add", "-q", "-b", "side", str(other))
        self.assertIn(
            self.refused(repo, other / "state"),
            ("state_dir_inside_project_worktree", "state_dir_inside_target_repository"),
        )

    def test_inside_plugin_installation(self):
        repo = init_repository(self.root / "repo")
        reason = self.refused(repo, SCRIPTS.parent / "state")
        self.assertIn(reason, ("state_dir_inside_plugin_repository", "state_dir_inside_plugin_installation"))

    def test_outside_directory_is_accepted(self):
        repo = init_repository(self.root / "repo")
        code, _ = run_state(repo, "where", environ={"DCA_STATE_DIR": str(self.state)})
        self.assertEqual(code, 0)


class RecordLifecycle(TemporaryDirectoryCase):
    def setUp(self):
        super().setUp()
        self.repo = init_repository(self.root / "repo")
        self.environ = {"DCA_STATE_DIR": str(self.state)}

    def run_state(self, *arguments, stdin=None):
        return run_state(self.repo, *arguments, environ=self.environ, stdin=stdin)

    def create(self, flow="reservation", label="予約"):
        code, payload = self.run_state("create", "--flow", flow, "--label", label)
        self.assertEqual(code, 0, payload)
        return payload

    def test_create_reports_new_record_target_and_written_path(self):
        payload = self.create()
        self.assertTrue(payload["new_record"])
        self.assertEqual(payload["target"]["worktree_root"], str(self.repo))
        self.assertTrue(payload["written_to"].startswith(str(self.state)))
        self.assertFalse(any(Path(self.repo).glob("**/record.json")))

    def test_create_twice_is_a_conflict(self):
        self.create()
        code, payload = self.run_state("create", "--flow", "reservation", "--label", "予約")
        self.assertEqual(code, 3)
        self.assertEqual(payload["reason"], "record_exists")

    def test_flow_is_rediscoverable_through_list_and_read(self):
        created = self.create()
        _, listed = self.run_state("list")
        self.assertEqual([item["flow"] for item in listed["flows"]], ["reservation"])
        self.assertEqual(listed["flows"][0]["label"], "予約")
        self.assertEqual(listed["flows"][0]["token"], created["token"])
        code, read = self.run_state("read", "--flow", "reservation")
        self.assertEqual(code, 0)
        self.assertEqual(read["record"]["flow"]["label"], "予約")

    def test_read_of_unknown_flow_does_not_create_anything(self):
        code, payload = self.run_state("read", "--flow", "unknown")
        self.assertEqual(code, 2)
        self.assertEqual(payload["status"], "not_found")
        self.assertFalse(self.state.exists())

    def test_update_of_unknown_flow_does_not_create_anything(self):
        code, payload = self.run_state(
            "observe", "--flow", "unknown", "--expected-token", "absent", stdin=json.dumps({"summary": "x"})
        )
        self.assertEqual(payload["status"], "not_found")
        self.assertEqual(code, 2)
        self.assertFalse(self.state.exists())

    def test_invalid_flow_key_is_refused(self):
        code, payload = self.run_state("create", "--flow", "../escape", "--label", "x")
        self.assertEqual(code, 2)
        self.assertEqual(payload["reason"], "flow_key_invalid")

    def test_stale_token_conflicts_and_keeps_record(self):
        created = self.create()
        first_code, first = self.run_state(
            "observe", "--flow", "reservation", "--expected-token", created["token"], stdin=json.dumps({"summary": "a"})
        )
        self.assertEqual(first_code, 0)
        code, payload = self.run_state(
            "observe", "--flow", "reservation", "--expected-token", created["token"], stdin=json.dumps({"summary": "b"})
        )
        self.assertEqual(code, 3)
        self.assertEqual(payload["reason"], "token_mismatch")
        self.assertEqual(payload["current_token"], first["token"])
        _, read = self.run_state("read", "--flow", "reservation")
        checkout = read["record"]["checkouts"][read["target"]["checkout_key"]]
        self.assertEqual([item["summary"] for item in checkout["observations"]], ["a"])

    def test_concurrent_updates_with_same_token_allow_exactly_one(self):
        created = self.create()
        processes = [
            spawn_state(
                self.repo,
                "observe",
                "--flow",
                "reservation",
                "--expected-token",
                created["token"],
                environ=self.environ,
            )
            for _ in range(4)
        ]
        codes = []
        for number, process in enumerate(processes):
            process.communicate(json.dumps({"summary": "writer-%d" % number}))
            codes.append(process.returncode)
        self.assertEqual(sorted(codes), [0, 3, 3, 3])
        _, read = self.run_state("read", "--flow", "reservation")
        checkout = read["record"]["checkouts"][read["target"]["checkout_key"]]
        self.assertEqual(len(checkout["observations"]), 1)

    def managed_paths(self):
        _, where = self.run_state("where")
        project = Path(where["location"]["state_dir"]) / where["location"]["project_key"]
        return project, project / "reservation"

    def assert_repository_untouched(self):
        self.assertEqual(git(self.repo, "status", "--porcelain"), "")
        self.assertEqual((self.repo / "README.md").read_text(encoding="utf-8"), "first\n")

    def test_symlinked_project_directory_cannot_redirect_writes_into_repository(self):
        project, _ = self.managed_paths()
        self.state.mkdir()
        project.symlink_to(self.repo, target_is_directory=True)
        for arguments in (("create", "--flow", "reservation", "--label", "予約"), ("list",), ("where",)):
            code, payload = self.run_state(*arguments)
            self.assertEqual(code, 2, payload)
            self.assertEqual(payload["status"], "refused")
        self.assert_repository_untouched()

    def test_symlinked_flow_directory_cannot_redirect_writes_into_repository(self):
        project, flow = self.managed_paths()
        project.mkdir(parents=True)
        flow.symlink_to(self.repo, target_is_directory=True)
        for arguments in (
            ("create", "--flow", "reservation", "--label", "予約"),
            ("read", "--flow", "reservation"),
        ):
            code, payload = self.run_state(*arguments)
            self.assertEqual(code, 2, payload)
            self.assertEqual(payload["status"], "refused")
        self.assert_repository_untouched()

    def test_symlinked_record_file_is_never_followed(self):
        created = self.create()
        _, flow = self.managed_paths()
        record = flow / "record.json"
        record.unlink()
        record.symlink_to(self.repo / "README.md")
        code, payload = self.run_state(
            "observe", "--flow", "reservation", "--expected-token", created["token"], stdin=json.dumps({"summary": "x"})
        )
        self.assertEqual(code, 2, payload)
        self.assertEqual(payload["reason"], "managed_path_not_plain_file")
        self.assert_repository_untouched()

    def test_symlinked_lock_file_is_never_followed(self):
        created = self.create()
        _, flow = self.managed_paths()
        lock = flow / "record.lock"
        lock.unlink()
        lock.symlink_to(self.repo / "README.md")
        code, payload = self.run_state(
            "observe", "--flow", "reservation", "--expected-token", created["token"], stdin=json.dumps({"summary": "x"})
        )
        self.assertEqual(code, 2, payload)
        self.assertEqual(payload["reason"], "managed_path_not_plain_file")
        self.assert_repository_untouched()

    def test_special_files_in_place_of_managed_files_return_errors_without_hanging(self):
        created = self.create()
        project, flow = self.managed_paths()
        for victim in (flow / "record.json", flow / "record.lock", project / "project.json"):
            victim.unlink()
            os.mkfifo(str(victim))
        for arguments, stdin in (
            (("read", "--flow", "reservation"), ""),
            (("list",), ""),
            (("observe", "--flow", "reservation", "--expected-token", created["token"]), json.dumps({"summary": "x"})),
            (("create", "--flow", "other", "--label", "別"), ""),
        ):
            process = spawn_state(self.repo, *arguments, environ=self.environ)
            output, _ = process.communicate(stdin, timeout=20)
            self.assertEqual(process.returncode, 2, output)
            self.assertEqual(json.loads(output)["status"], "refused")

    def test_verification_records_the_running_plugin_version(self):
        token = self.create()["token"]
        _, read = self.run_state("read", "--flow", "reservation")
        record_path = self.managed_paths()[1] / "record.json"
        stored = json.loads(record_path.read_text(encoding="utf-8"))
        stored["plugin"]["version"] = "0.0.0-created-earlier"
        record_path.write_text(json.dumps(stored), encoding="utf-8")
        _, listed = self.run_state("list")
        code, updated = self.run_state(
            "verify",
            "--flow",
            "reservation",
            "--expected-token",
            listed["flows"][0]["token"],
            stdin=self.verification("未実施"),
        )
        self.assertEqual(code, 0, updated)
        _, after = self.run_state("read", "--flow", "reservation")
        checkout = after["record"]["checkouts"][after["target"]["checkout_key"]]
        self.assertEqual(checkout["verifications"][0]["plugin_version"], dca_state.plugin_version())
        self.assertNotEqual(checkout["verifications"][0]["plugin_version"], "0.0.0-created-earlier")

    def test_malformed_record_is_rejected_by_read_and_update(self):
        created = self.create()
        record_path = self.managed_paths()[1] / "record.json"
        stored = json.loads(record_path.read_text(encoding="utf-8"))
        stored["schema"] = 99
        record_path.write_text(json.dumps(stored), encoding="utf-8")
        _, listed = self.run_state("list")
        self.assertEqual(listed["flows"][0]["error"], "record_schema_unsupported")
        token = hashlib.sha256(record_path.read_bytes()).hexdigest()
        for arguments, stdin in (
            (("read", "--flow", "reservation"), None),
            (("observe", "--flow", "reservation", "--expected-token", token), json.dumps({"summary": "x"})),
        ):
            code, payload = self.run_state(*arguments, stdin=stdin)
            self.assertEqual(code, 2, payload)
            self.assertEqual(payload["reason"], "record_schema_unsupported")

    def test_unwritable_state_location_is_reported_as_not_saved(self):
        locked_parent = self.root / "locked"
        locked_parent.mkdir()
        locked_parent.chmod(0o500)
        self.addCleanup(lambda: locked_parent.chmod(0o700))
        code, payload = run_state(
            self.repo,
            "create",
            "--flow",
            "reservation",
            "--label",
            "予約",
            environ={"DCA_STATE_DIR": str(locked_parent / "state")},
        )
        self.assertEqual(code, 2)
        self.assertEqual(payload["status"], "not_saved")
        self.assertFalse((self.repo / "state").exists())

    def identity(self, *paths):
        arguments = []
        for path in paths:
            arguments += ["--path", path]
        return dca_source.identify(str(self.repo), paths)

    def verification(self, status, before=None, after=None):
        payload = {
            "guarantee": "既存の挙動",
            "target": "README.md",
            "method": "手動確認",
            "status": status,
        }
        if before is not None:
            payload["identity_before"] = before
            payload["identity_after"] = after
        return json.dumps(payload, ensure_ascii=False)

    def test_verifications_are_append_only_and_survive_agreement_updates(self):
        token = self.create()["token"]
        _, first = self.run_state(
            "verify", "--flow", "reservation", "--expected-token", token, stdin=self.verification("未実施")
        )
        _, second = self.run_state(
            "verify", "--flow", "reservation", "--expected-token", first["token"], stdin=self.verification("失敗")
        )
        agreements = json.dumps(
            [{"statement": "再試行は明示操作", "scope": "予約", "reason": "重複の防止", "source": "利用者"}],
            ensure_ascii=False,
        )
        code, third = self.run_state(
            "agree", "--flow", "reservation", "--expected-token", second["token"], "--confirmed", stdin=agreements
        )
        self.assertEqual(code, 0, third)
        _, read = self.run_state("read", "--flow", "reservation")
        checkout = read["record"]["checkouts"][read["target"]["checkout_key"]]
        self.assertEqual([item["status"] for item in checkout["verifications"]], ["未実施", "失敗"])
        self.assertEqual([item["id"] for item in checkout["verifications"]], [1, 2])
        self.assertEqual(read["record"]["agreements"]["items"][0]["statement"], "再試行は明示操作")

    def test_agreement_update_requires_final_confirmation(self):
        token = self.create()["token"]
        code, payload = self.run_state(
            "agree",
            "--flow",
            "reservation",
            "--expected-token",
            token,
            stdin=json.dumps([{"statement": "a", "scope": "b", "reason": "c", "source": "d"}]),
        )
        self.assertEqual(code, 2)
        self.assertEqual(payload["reason"], "agreement_update_requires_confirmation")

    def test_confirmed_verification_requires_identical_identity(self):
        token = self.create()["token"]
        before = self.identity("README.md")
        (self.repo / "README.md").write_text("changed during verification\n", encoding="utf-8")
        after = self.identity("README.md")
        code, payload = self.run_state(
            "verify",
            "--flow",
            "reservation",
            "--expected-token",
            token,
            stdin=self.verification("確認済み", before, after),
        )
        self.assertEqual(code, 2)
        self.assertEqual(payload["reason"], "source_changed_during_verification")

    def test_confirmed_verification_with_identical_identity_is_recorded(self):
        token = self.create()["token"]
        before = self.identity("README.md")
        after = self.identity("README.md")
        code, payload = self.run_state(
            "verify",
            "--flow",
            "reservation",
            "--expected-token",
            token,
            stdin=self.verification("確認済み", before, after),
        )
        self.assertEqual(code, 0, payload)

    def test_confirmed_verification_without_identity_is_invalid(self):
        token = self.create()["token"]
        code, payload = self.run_state(
            "verify", "--flow", "reservation", "--expected-token", token, stdin=self.verification("確認済み")
        )
        self.assertEqual(code, 2)
        self.assertEqual(payload["reason"], "identity_required_for_confirmed")


class ProjectIdentity(TemporaryDirectoryCase):
    def setUp(self):
        super().setUp()
        self.environ = {"DCA_STATE_DIR": str(self.state)}

    def test_separate_clone_is_separate_project(self):
        first = init_repository(self.root / "first")
        clone = self.root / "clone"
        git(self.root, "clone", "-q", str(first), str(clone))
        _, first_where = run_state(first, "where", environ=self.environ)
        _, clone_where = run_state(clone, "where", environ=self.environ)
        self.assertNotEqual(first_where["location"]["project_key"], clone_where["location"]["project_key"])

    def test_worktrees_share_project_but_not_checkout(self):
        repo = init_repository(self.root / "repo")
        other = self.root / "other"
        git(repo, "worktree", "add", "-q", "-b", "side", str(other))
        _, main_where = run_state(repo, "where", environ=self.environ)
        _, other_where = run_state(other, "where", environ=self.environ)
        self.assertEqual(main_where["location"]["project_key"], other_where["location"]["project_key"])
        self.assertNotEqual(main_where["target"]["checkout_key"], other_where["target"]["checkout_key"])

    def test_same_path_with_different_history_is_refused(self):
        path = self.root / "repo"
        init_repository(path)
        code, _ = run_state(path, "create", "--flow", "reservation", "--label", "予約", environ=self.environ)
        self.assertEqual(code, 0)
        shutil.rmtree(str(path))
        init_repository(path, files={"other.txt": "different history\n"}, message="unrelated")
        code, payload = run_state(path, "list", environ=self.environ)
        self.assertEqual(code, 2)
        self.assertEqual(payload["reason"], "project_identity_mismatch")

    def test_other_checkout_records_are_not_mixed(self):
        repo = init_repository(self.root / "repo")
        other = self.root / "other"
        git(repo, "worktree", "add", "-q", "-b", "side", str(other))
        _, created = run_state(repo, "create", "--flow", "reservation", "--label", "予約", environ=self.environ)
        _, observed = run_state(
            repo,
            "observe",
            "--flow",
            "reservation",
            "--expected-token",
            created["token"],
            environ=self.environ,
            stdin=json.dumps({"summary": "main の観測"}),
        )
        code, read = run_state(other, "read", "--flow", "reservation", environ=self.environ)
        self.assertEqual(code, 0)
        self.assertNotIn(read["target"]["checkout_key"], read["record"]["checkouts"])
        self.assertEqual(len(read["other_checkouts"]), 1)
        self.assertEqual(read["token"], observed["token"])


if __name__ == "__main__":
    unittest.main()
