import hashlib
import os
import subprocess
from pathlib import Path


class GitError(Exception):
    pass


def run_git(repo, *args, check=True, text=True):
    command = ["git", "--no-optional-locks", "--literal-pathspecs", "-C", str(repo), *args]
    environment = dict(os.environ)
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    environment["GIT_LITERAL_PATHSPECS"] = "1"
    environment["LC_ALL"] = "C"
    try:
        completed = subprocess.run(command, capture_output=True, env=environment)
    except OSError as error:
        raise GitError("git を実行できない: %s" % error)
    if check and completed.returncode != 0:
        message = completed.stderr.decode("utf-8", "replace").strip()
        raise GitError("git %s が失敗した: %s" % (" ".join(args), message))
    if text:
        return completed.returncode, completed.stdout.decode("utf-8", "replace")
    return completed.returncode, completed.stdout


def digest(value, length=None):
    full = hashlib.sha256(value.encode("utf-8")).hexdigest()
    return full if length is None else full[:length]


def canonical(path):
    return Path(os.path.realpath(str(path)))


def repo_facts(path):
    _, output = run_git(path, "rev-parse", "--show-toplevel", "--absolute-git-dir", "--git-common-dir")
    lines = output.splitlines()
    if len(lines) != 3:
        raise GitError("リポジトリの場所を取得できない: %s" % path)
    worktree_root = canonical(lines[0])
    git_dir = canonical(lines[1])
    common_raw = Path(lines[2])
    common_dir = canonical(common_raw if common_raw.is_absolute() else Path(path) / common_raw)
    head_code, head_output = run_git(path, "rev-parse", "--verify", "-q", "HEAD", check=False)
    head = head_output.strip() if head_code == 0 else None
    roots = []
    if head:
        _, roots_output = run_git(path, "rev-list", "--max-parents=0", "HEAD")
        roots = sorted(roots_output.split())
    _, listing = run_git(path, "worktree", "list", "--porcelain")
    worktrees = [
        canonical(line[len("worktree "):])
        for line in listing.splitlines()
        if line.startswith("worktree ")
    ]
    return {
        "worktree_root": worktree_root,
        "git_dir": git_dir,
        "common_dir": common_dir,
        "head": head,
        "root_commits": roots,
        "worktrees": worktrees,
    }


def try_toplevel(path):
    try:
        code, output = run_git(path, "rev-parse", "--show-toplevel", check=False)
    except GitError:
        return None
    if code != 0 or not output.strip():
        return None
    return canonical(output.strip())
