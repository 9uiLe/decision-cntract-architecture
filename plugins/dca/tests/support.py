import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import dca_state  # noqa: E402

GIT_ENVIRONMENT = {
    "GIT_AUTHOR_NAME": "tester",
    "GIT_AUTHOR_EMAIL": "tester@example.invalid",
    "GIT_COMMITTER_NAME": "tester",
    "GIT_COMMITTER_EMAIL": "tester@example.invalid",
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_SYSTEM": os.devnull,
}


def git(repo, *args):
    environment = dict(os.environ)
    environment.update(GIT_ENVIRONMENT)
    completed = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, env=environment, check=True
    )
    return completed.stdout.decode("utf-8")


def init_repository(path, files=None, message="first"):
    Path(path).mkdir(parents=True, exist_ok=True)
    git(path, "init", "-q", "-b", "main")
    for name, content in (files or {"README.md": "first\n"}).items():
        target = Path(path) / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    git(path, "add", "-A")
    git(path, "commit", "-q", "-m", message)
    return Path(os.path.realpath(str(path)))


def run_state(repo, *arguments, environ=None, stdin=None):
    environment = {"HOME": os.environ.get("HOME", "/nonexistent")}
    environment.update(environ or {})
    output = io.StringIO()
    previous_stdin = sys.stdin
    sys.stdin = io.StringIO(stdin if stdin is not None else "")
    try:
        with contextlib.redirect_stdout(output):
            code = dca_state.main(["--repo", str(repo), *arguments], environ=environment)
    finally:
        sys.stdin = previous_stdin
    return code, json.loads(output.getvalue())


def spawn_state(repo, *arguments, environ=None):
    environment = dict(os.environ)
    environment.update(environ or {})
    return subprocess.Popen(
        [sys.executable, str(SCRIPTS / "dca_state.py"), "--repo", str(repo), *arguments],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=environment,
        text=True,
    )


class TemporaryDirectoryCase(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory(prefix="dca-test-")
        self.addCleanup(self._temporary.cleanup)
        self.root = Path(os.path.realpath(self._temporary.name))
        self.state = self.root / "state"
