import argparse
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import time
from pathlib import Path

import dca_git
import dca_source

SCHEMA = 1
FLOW_KEY = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
VERIFICATION_STATUSES = ("確認済み", "失敗", "未実施", "環境不足で未検証", "要再確認")
LOCK_TIMEOUT_SECONDS = 10
ABSENT = "absent"
PLUGIN_ROOT = Path(__file__).resolve().parents[1]


class StateError(Exception):
    def __init__(self, status, reason, **details):
        super().__init__(reason)
        self.status = status
        self.reason = reason
        self.details = details


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def plugin_version():
    with open(PLUGIN_ROOT / "plugin.json", "r", encoding="utf-8") as handle:
        return json.load(handle)["version"]


def absolute_value(name, value):
    if not os.path.isabs(value):
        raise StateError("refused", "state_dir_not_absolute", variable=name, value=value)
    return Path(os.path.normpath(value))


def resolve_state_dir(environ):
    explicit = environ.get("DCA_STATE_DIR")
    if explicit:
        return absolute_value("DCA_STATE_DIR", explicit), "DCA_STATE_DIR"
    xdg = environ.get("XDG_STATE_HOME")
    if xdg:
        return absolute_value("XDG_STATE_HOME", xdg) / "dca", "XDG_STATE_HOME"
    home = environ.get("HOME") or os.path.expanduser("~")
    if not os.path.isabs(home):
        raise StateError("refused", "home_not_resolved")
    return Path(home) / ".local" / "state" / "dca", "default"


def deepest_existing(path):
    current = Path(os.path.abspath(str(path)))
    while not os.path.lexists(str(current)) and current != current.parent:
        current = current.parent
    return current


def same_file(first, second):
    try:
        return os.path.samefile(str(first), str(second))
    except OSError:
        return False


def forbidden_roots(facts):
    roots = {}
    roots[str(facts["worktree_root"])] = "target_repository"
    roots[str(facts["git_dir"])] = "git_dir"
    roots[str(facts["common_dir"])] = "git_common_dir"
    for worktree in facts["worktrees"]:
        roots.setdefault(str(worktree), "project_worktree")
    plugin_repo = dca_git.try_toplevel(PLUGIN_ROOT)
    if plugin_repo is not None:
        roots.setdefault(str(plugin_repo), "plugin_repository")
    roots.setdefault(str(dca_git.canonical(PLUGIN_ROOT)), "plugin_installation")
    return roots


def reject_forbidden_location(location, facts):
    anchor = deepest_existing(location)
    chain = [anchor, *anchor.parents]
    real = os.path.realpath(str(location))
    for root, role in forbidden_roots(facts).items():
        if real == root or real.startswith(root + os.sep):
            raise StateError("refused", "state_dir_inside_" + role, location=str(location), root=root)
        if any(same_file(ancestor, root) for ancestor in chain):
            raise StateError("refused", "state_dir_inside_" + role, location=str(location), root=root)


def validate_flow_key(flow_key):
    if not FLOW_KEY.match(flow_key or ""):
        raise StateError("invalid", "flow_key_invalid", flow=flow_key)


def project_key(facts):
    common = facts["common_dir"]
    base = common.parent.name if common.name == ".git" else common.name
    name = re.sub(r"[^a-z0-9]+", "-", base.lower()).strip("-") or "project"
    return "%s-%s" % (name[:24], dca_git.digest(str(common), 16))


def checkout_key(facts):
    return dca_git.digest("%s\0%s" % (facts["worktree_root"], facts["git_dir"]), 12)


def require_plain_directory(path):
    try:
        mode = os.lstat(str(path)).st_mode
    except FileNotFoundError:
        return False
    if not stat.S_ISDIR(mode):
        raise StateError("refused", "managed_path_not_plain_directory", path=str(path))
    return True


def require_plain_file(path):
    try:
        mode = os.lstat(str(path)).st_mode
    except FileNotFoundError:
        return False
    if not stat.S_ISREG(mode):
        raise StateError("refused", "managed_path_not_plain_file", path=str(path))
    return True


def token_of(raw):
    return ABSENT if raw is None else hashlib.sha256(raw).hexdigest()


def read_raw(path):
    if not require_plain_file(path):
        return None
    try:
        descriptor = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    except OSError:
        raise StateError("refused", "managed_path_not_plain_file", path=str(path))
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise StateError("refused", "managed_path_not_plain_file", path=str(path))
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            return handle.read()
    finally:
        os.close(descriptor)


def serialize(record):
    return (json.dumps(record, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


@contextlib.contextmanager
def locked(directory, lock_name):
    lock_path = directory / lock_name
    require_plain_file(lock_path)
    try:
        descriptor = os.open(str(lock_path), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    except OSError as error:
        raise StateError("not_saved", "lock_unavailable", path=str(lock_path), detail=str(error))
    try:
        deadline = time.monotonic() + LOCK_TIMEOUT_SECONDS
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise StateError("not_saved", "lock_timeout", path=str(lock_path))
                time.sleep(0.05)
        yield
    finally:
        os.close(descriptor)


def atomic_write(path, payload):
    require_plain_file(path)
    directory = path.parent
    handle, temporary = tempfile.mkstemp(dir=str(directory), prefix=".write-")
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, str(path))
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(temporary)
        raise
    directory_descriptor = os.open(str(directory), os.O_RDONLY)
    try:
        os.fsync(directory_descriptor)
    finally:
        os.close(directory_descriptor)


class Context:
    def __init__(self, repo, environ):
        try:
            self.facts = dca_git.repo_facts(repo)
        except dca_git.GitError as error:
            raise StateError("refused", "not_a_git_repository", detail=str(error))
        self.state_dir, self.selection = resolve_state_dir(environ)
        reject_forbidden_location(self.state_dir, self.facts)
        self.project_key = project_key(self.facts)
        self.checkout_key = checkout_key(self.facts)
        self.project_dir = self.state_dir / self.project_key

    def flow_dir(self, flow_key):
        validate_flow_key(flow_key)
        return self.project_dir / flow_key

    def inspect_directory(self, path):
        exists = require_plain_directory(path)
        if exists:
            reject_forbidden_location(path, self.facts)
        return exists

    def prepare_directory(self, path):
        os.makedirs(str(self.state_dir), mode=0o700, exist_ok=True)
        chain = [self.project_dir] if path == self.project_dir else [self.project_dir, path]
        for item in chain:
            if not require_plain_directory(item):
                os.mkdir(str(item), 0o700)
            require_plain_directory(item)
            reject_forbidden_location(item, self.facts)

    def target(self):
        return {
            "worktree_root": str(self.facts["worktree_root"]),
            "git_dir": str(self.facts["git_dir"]),
            "checkout_key": self.checkout_key,
            "head": self.facts["head"],
        }

    def location(self):
        return {
            "state_dir": str(self.state_dir),
            "selected_by": self.selection,
            "project_key": self.project_key,
        }


def check_identity(common_dir, history_roots, context):
    roots = context.facts["root_commits"]
    if common_dir != str(context.facts["common_dir"]) or (history_roots and history_roots != roots):
        raise StateError(
            "refused",
            "project_identity_mismatch",
            stored_history=history_roots,
            current_history=roots,
        )


def validate_record(record, context, flow_key):
    try:
        if record["schema"] != SCHEMA:
            raise StateError("invalid", "record_schema_unsupported", schema=record["schema"])
        check_identity(record["project"]["common_dir"], record["project"]["history_roots"], context)
        if record["flow"]["key"] != flow_key:
            raise StateError("invalid", "record_flow_key_mismatch", flow=flow_key)
        valid_shape = (
            isinstance(record["agreements"]["items"], list)
            and isinstance(record["checkouts"], dict)
            and isinstance(record["revision"], int)
        )
        if not valid_shape:
            raise StateError("invalid", "record_malformed", flow=flow_key)
    except (KeyError, TypeError):
        raise StateError("invalid", "record_malformed", flow=flow_key)
    return record


def parse_record(raw, context, flow_key):
    try:
        record = json.loads(raw.decode("utf-8"))
    except ValueError:
        raise StateError("invalid", "record_not_json", flow=flow_key)
    return validate_record(record, context, flow_key)


def inspect_project(context):
    if not context.inspect_directory(context.project_dir):
        return "new"
    raw = read_raw(context.project_dir / "project.json")
    if raw is None:
        return "new"
    stored = json.loads(raw.decode("utf-8"))
    check_identity(stored.get("common_dir"), stored.get("history_roots", []), context)
    return "existing"


def ensure_project(context):
    context.prepare_directory(context.project_dir)
    path = context.project_dir / "project.json"
    with locked(context.project_dir, "project.lock"):
        raw = read_raw(path)
        stored_roots = []
        if raw is not None:
            stored = json.loads(raw.decode("utf-8"))
            stored_roots = stored.get("history_roots", [])
            check_identity(stored.get("common_dir"), stored_roots, context)
        roots = context.facts["root_commits"]
        if raw is None or (not stored_roots and roots):
            metadata = {
                "schema": SCHEMA,
                "key": context.project_key,
                "common_dir": str(context.facts["common_dir"]),
                "history_roots": roots,
            }
            atomic_write(path, serialize(metadata))
    return "existing" if raw is not None else "new"


def new_record(context, flow_key, label):
    return {
        "schema": SCHEMA,
        "plugin": {"name": "dca", "version": plugin_version()},
        "project": {
            "key": context.project_key,
            "common_dir": str(context.facts["common_dir"]),
            "history_roots": context.facts["root_commits"],
        },
        "flow": {"key": flow_key, "label": label, "created_at": now()},
        "agreements": {"items": [], "confirmed_at": None},
        "checkouts": {},
        "revision": 0,
    }


def checkout_entry(record, context):
    checkouts = record["checkouts"]
    if context.checkout_key not in checkouts:
        checkouts[context.checkout_key] = {
            "worktree_root": str(context.facts["worktree_root"]),
            "git_dir": str(context.facts["git_dir"]),
            "first_seen": now(),
            "observations": [],
            "verifications": [],
            "progress": {},
        }
    return checkouts[context.checkout_key]


def load_record(context, flow_key):
    directory = context.flow_dir(flow_key)
    inspect_project(context)
    if not context.inspect_directory(directory):
        raise StateError("not_found", "record_not_found", flow=flow_key)
    raw = read_raw(directory / "record.json")
    if raw is None:
        raise StateError("not_found", "record_not_found", flow=flow_key)
    return parse_record(raw, context, flow_key), token_of(raw)


def mutate(context, flow_key, expected_token, change):
    directory = context.flow_dir(flow_key)
    load_record(context, flow_key)
    path = directory / "record.json"
    with locked(directory, "record.lock"):
        raw = read_raw(path)
        if raw is None:
            raise StateError("not_found", "record_not_found", flow=flow_key)
        current = token_of(raw)
        if current != expected_token:
            raise StateError("conflict", "token_mismatch", current_token=current, expected_token=expected_token)
        record = parse_record(raw, context, flow_key)
        result = change(record)
        record["revision"] += 1
        payload = serialize(record)
        atomic_write(path, payload)
    return {"token": token_of(payload), "revision": record["revision"], "result": result}


def next_id(entries):
    return max([entry["id"] for entry in entries], default=0) + 1


def require_object(payload, name):
    if not isinstance(payload, dict):
        raise StateError("invalid", "payload_not_object", payload=name)
    return payload


def require_text(payload, key):
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise StateError("invalid", "payload_field_missing", field=key)
    return value


def apply_observation(context, payload):
    require_object(payload, "observation")
    require_text(payload, "summary")

    def change(record):
        entry = checkout_entry(record, context)
        stored = dict(payload)
        stored["id"] = next_id(entry["observations"])
        stored["recorded_at"] = now()
        entry["observations"].append(stored)
        return {"id": stored["id"]}

    return change


def apply_progress(context, payload):
    require_object(payload, "progress")
    require_text(payload, "state")

    def change(record):
        entry = checkout_entry(record, context)
        entry["progress"] = dict(payload, recorded_at=now())
        return {}

    return change


def apply_verification(context, payload):
    require_object(payload, "verification")
    for field in ("guarantee", "target", "method"):
        require_text(payload, field)
    status = payload.get("status")
    if status not in VERIFICATION_STATUSES:
        raise StateError("invalid", "verification_status_invalid", allowed=list(VERIFICATION_STATUSES))
    if status == "確認済み":
        before = payload.get("identity_before")
        after = payload.get("identity_after")
        if not isinstance(before, dict) or not isinstance(after, dict):
            raise StateError("invalid", "identity_required_for_confirmed")
        try:
            comparison = dca_source.compare(before, after, str(context.facts["worktree_root"]))
        except dca_source.SourceError as error:
            raise StateError("invalid", "identity_invalid", detail=str(error))
        if before["checkout"]["key"] != context.checkout_key or not comparison["unchanged_within_coverage"]:
            raise StateError("refused", "source_changed_during_verification", comparison=comparison)
        if comparison["truncated"]:
            raise StateError("refused", "identity_truncated", comparison=comparison)
        payload = dict(
            payload,
            identity_scope="complete" if comparison["identical"] else "limited",
            identity_limits=comparison["unconfirmed"],
            identity_limits_count=comparison["unconfirmed_count"],
        )
    running_version = plugin_version()

    def change(record):
        entry = checkout_entry(record, context)
        stored = dict(payload)
        stored["id"] = next_id(entry["verifications"])
        stored["recorded_at"] = now()
        stored["plugin_version"] = running_version
        entry["verifications"].append(stored)
        return {"id": stored["id"]}

    return change


def apply_agreements(payload, confirmed):
    if not confirmed:
        raise StateError("refused", "agreement_update_requires_confirmation")
    if not isinstance(payload, list):
        raise StateError("invalid", "payload_not_list", payload="agreements")
    for item in payload:
        require_object(item, "agreement")
        for field in ("statement", "scope", "reason", "source"):
            require_text(item, field)

    def change(record):
        agreements = record["agreements"]
        agreements["items"] = [dict(item, id=index + 1) for index, item in enumerate(payload)]
        agreements["confirmed_at"] = now()
        return {"count": len(payload)}

    return change


def flow_summaries(context):
    flows = []
    for entry in sorted(context.project_dir.iterdir(), key=lambda item: item.name):
        if entry.name in ("project.json", "project.lock"):
            continue
        require_plain_directory(entry)
        raw = read_raw(entry / "record.json")
        if raw is None:
            continue
        try:
            record = parse_record(raw, context, entry.name)
        except StateError as error:
            flows.append({"flow": entry.name, "error": error.reason})
            continue
        flows.append(
            {
                "flow": record["flow"]["key"],
                "label": record["flow"]["label"],
                "token": token_of(raw),
                "revision": record["revision"],
                "checkouts": sorted(record["checkouts"]),
                "has_current_checkout": context.checkout_key in record["checkouts"],
            }
        )
    return flows


def command_where(context, arguments):
    status = inspect_project(context)
    flows = []
    if status == "existing":
        flows = [item["flow"] for item in flow_summaries(context) if "error" not in item]
    return {
        "status": "ok",
        "location": context.location(),
        "project_record": status,
        "target": context.target(),
        "flows": flows,
    }


def command_list(context, arguments):
    status = inspect_project(context)
    flows = flow_summaries(context) if status == "existing" else []
    return {
        "status": "ok",
        "location": context.location(),
        "project_record": status,
        "target": context.target(),
        "flows": flows,
    }


def command_read(context, arguments):
    record, token = load_record(context, arguments.flow)
    others = sorted(key for key in record["checkouts"] if key != context.checkout_key)
    return {
        "status": "ok",
        "location": context.location(),
        "target": context.target(),
        "token": token,
        "other_checkouts": others,
        "record": record,
    }


def command_create(context, arguments):
    validate_flow_key(arguments.flow)
    label = (arguments.label or "").strip()
    if not label or len(label) > 200:
        raise StateError("invalid", "flow_label_invalid")
    directory = context.flow_dir(arguments.flow)
    project_status = ensure_project(context)
    context.prepare_directory(directory)
    with locked(directory, "record.lock"):
        path = directory / "record.json"
        if read_raw(path) is not None:
            raise StateError("conflict", "record_exists", flow=arguments.flow)
        payload = serialize(new_record(context, arguments.flow, label))
        atomic_write(path, payload)
    return {
        "status": "ok",
        "location": context.location(),
        "target": context.target(),
        "project_record": project_status,
        "new_record": True,
        "flow": arguments.flow,
        "token": token_of(payload),
        "written_to": str(directory / "record.json"),
    }


def read_payload():
    try:
        return json.load(sys.stdin)
    except ValueError as error:
        raise StateError("invalid", "payload_not_json", detail=str(error))


def command_update(context, arguments):
    payload = read_payload()
    builders = {
        "observe": lambda: apply_observation(context, payload),
        "progress": lambda: apply_progress(context, payload),
        "verify": lambda: apply_verification(context, payload),
        "agree": lambda: apply_agreements(payload, arguments.confirmed),
    }
    outcome = mutate(context, arguments.flow, arguments.expected_token, builders[arguments.command]())
    outcome.update(
        {
            "status": "ok",
            "location": context.location(),
            "target": context.target(),
            "written_to": str(context.flow_dir(arguments.flow) / "record.json"),
        }
    )
    return outcome


def build_parser():
    parser = argparse.ArgumentParser(prog="dca_state")
    parser.add_argument("--repo", default=os.getcwd())
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("where")
    commands.add_parser("list")
    read = commands.add_parser("read")
    read.add_argument("--flow", required=True)
    create = commands.add_parser("create")
    create.add_argument("--flow", required=True)
    create.add_argument("--label", required=True)
    for name in ("observe", "progress", "verify", "agree"):
        update = commands.add_parser(name)
        update.add_argument("--flow", required=True)
        update.add_argument("--expected-token", required=True)
        if name == "agree":
            update.add_argument("--confirmed", action="store_true")
    return parser


def emit(payload, code):
    json.dump(payload, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return code


def main(argv=None, environ=None):
    arguments = build_parser().parse_args(argv)
    environment = os.environ if environ is None else environ
    handlers = {
        "where": command_where,
        "list": command_list,
        "read": command_read,
        "create": command_create,
    }
    try:
        context = Context(arguments.repo, environment)
        handler = handlers.get(arguments.command, command_update)
        return emit(handler(context, arguments), 0)
    except StateError as error:
        payload = {"status": error.status, "reason": error.reason}
        payload.update(error.details)
        return emit(payload, 3 if error.status == "conflict" else 2)
    except (OSError, ValueError, KeyError, dca_git.GitError) as error:
        return emit({"status": "not_saved", "reason": "environment_error", "detail": str(error)}, 2)


if __name__ == "__main__":
    sys.exit(main())
