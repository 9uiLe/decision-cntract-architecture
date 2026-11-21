import argparse
import fnmatch
import hashlib
import json
import os
import platform
import posixpath
import re
import stat
import sys

import dca_git

SCHEMA = 1
MAX_RELEVANT = 2000
MAX_DIRTY = 2000
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_UNCONFIRMED_LISTED = 50
CHUNK = 200
OBJECT_ID = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")

SENSITIVE_PATTERNS = (
    ".env",
    ".env.*",
    "*.pem",
    "*.key",
    "*.p12",
    "*.pfx",
    "*.jks",
    "*.keystore",
    "id_rsa*",
    "id_ed25519*",
    "credentials*",
    "secrets*",
    ".netrc",
    "*.mobileprovision",
)

EXCLUDED = "excluded_sensitive"
UNSUPPORTED_ENCODING = "unsupported_encoding"
UNCONFIRMED_STATES = ("excluded_sensitive", "ignored", "submodule", "conflict", "outside_coverage")
UNCONFIRMED_KINDS = ("non_regular", "unsafe_parent", "unreadable", "too_large", "unsafe_changed")

COVERAGE_NOTES = (
    "Git の pathspec はリテラルとして扱い、glob と magic は解釈しない",
    "リネームは検出せず、旧パスと新パスを別々の変更として扱う",
    "UTF-8 として読めないパス名は内容を読まず、名前の hash だけを記録する",
    "親ディレクトリが symlink のパス、symlink、通常ファイル以外は内容を読まない",
    "無視されたファイルと、機微な名前のファイルの内容は識別に含めない",
    "サブモジュールの内部は識別に含めない",
    "環境変数、OS、ツールチェーンの版(git と python 以外)、ネットワークと外部サービスの状態は識別に含めない",
    "relevant に含めないファイルは、変更の有無だけを dirty の一覧で比較する",
)


class SourceError(Exception):
    pass


def is_sensitive(path):
    name = posixpath.basename(path).lower()
    return any(fnmatch.fnmatch(name, pattern) for pattern in SENSITIVE_PATTERNS)


def is_safe_relative(path):
    if not isinstance(path, str) or not path or os.path.isabs(path) or "\0" in path:
        return False
    return all(part not in ("", ".", "..") for part in path.split("/"))


def normalize(root, raw):
    absolute = raw if os.path.isabs(raw) else os.path.join(str(root), raw)
    normalized = os.path.normpath(absolute)
    root_text = os.path.normpath(str(root))
    if normalized != root_text and not normalized.startswith(root_text + os.sep):
        raise SourceError("リポジトリの外のパスは扱えない: %s" % raw)
    relative = os.path.relpath(normalized, root_text)
    if relative == ".":
        raise SourceError("リポジトリのルート全体は relevant にできない。対象のパスを指定する")
    return relative.replace(os.sep, "/")


def inspect_worktree(root, relative):
    current = str(root)
    parts = relative.split("/")
    for part in parts[:-1]:
        current = os.path.join(current, part)
        try:
            mode = os.lstat(current).st_mode
        except FileNotFoundError:
            return "absent", None
        except OSError:
            return "unreadable", None
        if not stat.S_ISDIR(mode):
            return "unsafe_parent", None
    full = os.path.join(current, parts[-1])
    try:
        info = os.lstat(full)
    except FileNotFoundError:
        return "absent", None
    except OSError:
        return "unreadable", None
    if stat.S_ISLNK(info.st_mode):
        try:
            target = os.readlink(full)
        except OSError:
            return "unreadable", None
        return "symlink", hashlib.sha256(("symlink:" + target).encode("utf-8", "surrogateescape")).hexdigest()
    if stat.S_ISDIR(info.st_mode):
        return "directory", None
    if not stat.S_ISREG(info.st_mode):
        return "non_regular", None
    if info.st_size > MAX_FILE_BYTES:
        return "too_large", None
    try:
        descriptor = os.open(full, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        return "unreadable", None
    try:
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
            return "unsafe_changed", None
        hasher = hashlib.sha256()
        while True:
            block = os.read(descriptor, 1 << 20)
            if not block:
                break
            hasher.update(block)
        return "regular", hasher.hexdigest()
    except OSError:
        return "unreadable", None
    finally:
        os.close(descriptor)


def chunks(items):
    for start in range(0, len(items), CHUNK):
        yield items[start:start + CHUNK]


class Findings:
    def __init__(self):
        self.undecodable = set()

    def decode(self, raw):
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            self.undecodable.add(hashlib.sha256(raw).hexdigest()[:16])
            return None

    def names(self, data):
        decoded = []
        for raw in data.split(b"\0"):
            if not raw:
                continue
            name = self.decode(raw)
            if name is not None and is_safe_relative(name):
                decoded.append(name)
        return decoded


def expand(root, paths, findings):
    expanded = []
    seen = set()
    for relative in paths:
        kind, _ = inspect_worktree(root, relative)
        if kind == "directory":
            _, output = dca_git.run_git(
                root, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", relative, text=False
            )
            members = findings.names(output)
        else:
            members = [relative]
        for member in members:
            if member not in seen:
                seen.add(member)
                expanded.append(member)
    if len(expanded) > MAX_RELEVANT:
        raise SourceError("relevant のパスが多すぎる(%d 件)。範囲を絞る" % len(expanded))
    return sorted(expanded)


def index_entries(root, paths, findings):
    entries = {}
    for group in chunks(paths):
        _, output = dca_git.run_git(root, "ls-files", "-s", "-z", "--", *group, text=False)
        for raw in output.split(b"\0"):
            if not raw:
                continue
            meta, _, raw_name = raw.partition(b"\t")
            name = findings.decode(raw_name)
            if name is None:
                continue
            mode, oid, stage = meta.decode("ascii").split(" ")
            entries.setdefault(name, []).append((mode, oid, int(stage)))
    return entries


def head_entries(root, paths, findings):
    entries = {}
    for group in chunks(paths):
        code, output = dca_git.run_git(root, "ls-tree", "-r", "-z", "HEAD", "--", *group, check=False, text=False)
        if code != 0:
            return entries
        for raw in output.split(b"\0"):
            if not raw:
                continue
            meta, _, raw_name = raw.partition(b"\t")
            name = findings.decode(raw_name)
            if name is None:
                continue
            mode, _, oid = meta.decode("ascii").split(" ")
            entries[name] = (mode, oid)
    return entries


def listed_untracked(root, paths, ignored, findings):
    found = set()
    arguments = ["ls-files", "-z", "--others", "--exclude-standard"]
    if ignored:
        arguments.append("--ignored")
    for group in chunks(paths):
        _, output = dca_git.run_git(root, *arguments, "--", *group, text=False)
        found.update(findings.names(output))
    return found


def describe_paths(root, paths, has_head, findings):
    index = index_entries(root, paths, findings)
    head = head_entries(root, paths, findings) if has_head else {}
    untracked = listed_untracked(root, paths, False, findings)
    ignored = listed_untracked(root, paths, True, findings)
    described = []
    for relative in paths:
        staged = index.get(relative, [])
        stage_zero = [item for item in staged if item[2] == 0]
        entry = {
            "path": relative,
            "state": None,
            "head_oid": None,
            "index_oid": None,
            "index_mode": None,
            "worktree_kind": None,
            "worktree_digest": None,
        }
        if is_sensitive(relative):
            entry["state"] = EXCLUDED
            described.append(entry)
            continue
        if any(item[0] == "160000" for item in staged):
            entry["state"] = "submodule"
            entry["index_oid"] = staged[0][1]
            entry["index_mode"] = staged[0][0]
            entry["head_oid"] = head.get(relative, (None, None))[1]
        elif staged and not stage_zero:
            entry["state"] = "conflict"
        elif stage_zero:
            entry["state"] = "tracked"
            entry["index_oid"] = stage_zero[0][1]
            entry["index_mode"] = stage_zero[0][0]
            entry["head_oid"] = head.get(relative, (None, None))[1]
            entry["worktree_kind"], entry["worktree_digest"] = inspect_worktree(root, relative)
        elif relative in ignored:
            entry["state"] = "ignored"
        elif relative in untracked:
            entry["state"] = "untracked"
            entry["worktree_kind"], entry["worktree_digest"] = inspect_worktree(root, relative)
        else:
            kind, digest_value = inspect_worktree(root, relative)
            if kind == "absent":
                entry["state"] = "missing"
                entry["head_oid"] = head.get(relative, (None, None))[1]
            else:
                entry["state"] = "outside_coverage"
                entry["worktree_kind"] = kind
        described.append(entry)
    return described


def status_records(root, findings):
    _, output = dca_git.run_git(
        root,
        "status",
        "--porcelain=v2",
        "-z",
        "--no-renames",
        "--untracked-files=all",
        "--ignore-submodules=none",
        text=False,
    )
    tokens = output.split(b"\0")
    records = []
    position = 0
    while position < len(tokens):
        raw = tokens[position]
        position += 1
        if not raw:
            continue
        kind = chr(raw[0])
        if kind == "?":
            prefix, raw_name = "?", raw[2:]
        elif kind in "12u":
            width = {"1": 8, "2": 9, "u": 10}[kind]
            pieces = raw.split(b" ", width)
            prefix = b" ".join(pieces[:width]).decode("ascii", "replace")
            raw_name = pieces[width]
            if kind == "2":
                position += 1
        else:
            continue
        name = findings.decode(raw_name)
        if name is None:
            records.append((UNSUPPORTED_ENCODING + ":" + hashlib.sha256(raw_name).hexdigest()[:16], prefix, None))
        elif is_safe_relative(name):
            records.append((name, prefix, name))
    return records


def dirty_baseline(root, findings):
    records = sorted(status_records(root, findings), key=lambda item: item[0])
    seen = set()
    unique = []
    for record in records:
        if record[0] not in seen:
            seen.add(record[0])
            unique.append(record)
    entries = {}
    for key, prefix, name in unique[:MAX_DIRTY]:
        if name is None:
            entries[key] = {"status": prefix, "worktree_kind": UNSUPPORTED_ENCODING, "digest": None}
        elif is_sensitive(name):
            entries[key] = {"status": prefix, "worktree_kind": EXCLUDED, "digest": None}
        else:
            kind, digest_value = inspect_worktree(root, name)
            entries[key] = {"status": prefix, "worktree_kind": kind, "digest": digest_value}
    return {"entries": entries, "count": len(unique), "truncated": len(unique) > MAX_DIRTY}


def identify(repo, raw_paths):
    facts = dca_git.repo_facts(repo)
    root = facts["worktree_root"]
    findings = Findings()
    relevant = expand(root, sorted({normalize(root, raw) for raw in raw_paths}), findings)
    checkout_key = dca_git.digest("%s\0%s" % (facts["worktree_root"], facts["git_dir"]), 12)
    _, git_version = dca_git.run_git(root, "--version")
    document = {
        "schema": SCHEMA,
        "checkout": {
            "key": checkout_key,
            "worktree_root": str(facts["worktree_root"]),
            "git_dir": str(facts["git_dir"]),
            "head": facts["head"],
        },
        "relevant": describe_paths(root, relevant, facts["head"] is not None, findings),
        "dirty": dirty_baseline(root, findings),
        "coverage": {
            "notes": list(COVERAGE_NOTES),
            "tools": {
                "git": git_version.strip(),
                "python": platform.python_version(),
                "platform": sys.platform,
            },
        },
    }
    document["coverage"]["undecodable_paths"] = sorted(findings.undecodable)
    return document


def validate(document, name):
    try:
        if document["schema"] != SCHEMA:
            raise SourceError("%s: schema が一致しない" % name)
        head = document["checkout"]["head"]
        if head is not None and not OBJECT_ID.match(head):
            raise SourceError("%s: checkout.head が object ID ではない" % name)
        if not isinstance(document["checkout"]["key"], str):
            raise SourceError("%s: checkout.key が文字列ではない" % name)
        for entry in document["relevant"]:
            if not is_safe_relative(entry["path"]):
                raise SourceError("%s: relevant に安全でないパスがある" % name)
            for field in ("state", "head_oid", "index_oid", "index_mode", "worktree_kind", "worktree_digest"):
                entry[field]
        entries = document["dirty"]["entries"]
        if not isinstance(entries, dict) or not isinstance(document["dirty"]["truncated"], bool):
            raise SourceError("%s: dirty の形式が不正" % name)
        for key, value in entries.items():
            value["status"], value["worktree_kind"], value["digest"]
        document["coverage"]["undecodable_paths"]
    except (KeyError, TypeError, AttributeError):
        raise SourceError("%s: 識別情報の形式が不正" % name)


def relevant_key(entry):
    return tuple(
        entry[field]
        for field in ("state", "head_oid", "index_oid", "index_mode", "worktree_kind", "worktree_digest")
    )


def collect_unconfirmed(document, scope):
    found = []
    for entry in document["relevant"]:
        if entry["state"] in UNCONFIRMED_STATES:
            found.append({"scope": "relevant", "side": scope, "path": entry["path"], "reason": entry["state"]})
        elif entry["worktree_kind"] in UNCONFIRMED_KINDS:
            found.append({"scope": "relevant", "side": scope, "path": entry["path"], "reason": entry["worktree_kind"]})
    for path, value in document["dirty"]["entries"].items():
        kind = value["worktree_kind"]
        if kind in UNCONFIRMED_KINDS or kind in (EXCLUDED, UNSUPPORTED_ENCODING):
            found.append({"scope": "dirty", "side": scope, "path": path, "reason": kind})
    if document["dirty"]["truncated"]:
        found.append(
            {"scope": "dirty", "side": scope, "path": None, "reason": "truncated", "count": document["dirty"]["count"]}
        )
    for digest_value in document["coverage"]["undecodable_paths"]:
        found.append({"scope": "coverage", "side": scope, "path": None, "reason": UNSUPPORTED_ENCODING, "name_hash": digest_value})
    return found


def head_diff_names(repo, old_head, new_head):
    for value in (old_head, new_head):
        if not OBJECT_ID.match(value):
            raise SourceError("object ID の形式が不正")
    code, output = dca_git.run_git(
        repo, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", old_head, new_head, "--",
        check=False,
        text=False,
    )
    if code != 0:
        return None
    return Findings().names(output)


def compare(before, after, repo=None):
    validate(before, "before")
    validate(after, "after")
    before_relevant = {entry["path"]: entry for entry in before["relevant"]}
    after_relevant = {entry["path"]: entry for entry in after["relevant"]}
    relevant = []
    for path in sorted(set(before_relevant) | set(after_relevant)):
        old = before_relevant.get(path)
        new = after_relevant.get(path)
        if old is None:
            change = "added"
        elif new is None:
            change = "removed"
        elif relevant_key(old) == relevant_key(new):
            change = "same"
        else:
            change = "changed"
        relevant.append({"path": path, "change": change})
    watched = set(before_relevant) | set(after_relevant)
    before_dirty = before["dirty"]["entries"]
    after_dirty = after["dirty"]["entries"]
    outside = sorted(
        path
        for path in set(before_dirty) | set(after_dirty)
        if path not in watched and before_dirty.get(path) != after_dirty.get(path)
    )
    head_changed = before["checkout"]["head"] != after["checkout"]["head"]
    head_diff = []
    head_diff_unavailable = False
    if head_changed:
        head_diff_unavailable = True
        old_head = before["checkout"]["head"]
        new_head = after["checkout"]["head"]
        if repo is not None and old_head and new_head:
            names = head_diff_names(repo, old_head, new_head)
            if names is not None:
                head_diff = sorted(name for name in names if name not in watched)
                head_diff_unavailable = False
    outside_all = sorted(set(outside) | set(head_diff))
    checkout_same = before["checkout"]["key"] == after["checkout"]["key"]
    relevant_unchanged = all(item["change"] == "same" for item in relevant)
    unconfirmed = collect_unconfirmed(before, "before") + collect_unconfirmed(after, "after")
    truncated = before["dirty"]["truncated"] or after["dirty"]["truncated"]
    unchanged = checkout_same and not head_changed and relevant_unchanged and not outside
    return {
        "checkout_same": checkout_same,
        "head_changed": head_changed,
        "relevant": relevant,
        "relevant_unchanged": relevant_unchanged,
        "outside_changes": outside_all,
        "head_diff_unavailable": head_diff_unavailable,
        "truncated": truncated,
        "unconfirmed": unconfirmed[:MAX_UNCONFIRMED_LISTED],
        "unconfirmed_count": len(unconfirmed),
        "unchanged_within_coverage": unchanged,
        "identical": unchanged and not unconfirmed,
        "requires_source_review": bool(outside_all) or head_diff_unavailable or not relevant_unchanged,
    }


def load(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def emit(payload, code=0):
    json.dump(payload, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(prog="dca_source")
    commands = parser.add_subparsers(dest="command", required=True)
    identify_parser = commands.add_parser("identify")
    identify_parser.add_argument("--repo", default=os.getcwd())
    identify_parser.add_argument("--path", action="append", default=[], required=True)
    compare_parser = commands.add_parser("compare")
    compare_parser.add_argument("--repo", default=None)
    compare_parser.add_argument("--before", required=True)
    compare_parser.add_argument("--after", required=True)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "identify":
            return emit(identify(arguments.repo, arguments.path))
        return emit(compare(load(arguments.before), load(arguments.after), arguments.repo))
    except (SourceError, dca_git.GitError, OSError, ValueError) as error:
        return emit({"status": "invalid", "reason": str(error)}, 2)


if __name__ == "__main__":
    sys.exit(main())
