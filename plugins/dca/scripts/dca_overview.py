import argparse
import bisect
import hashlib
import json
import math
import re
import struct
import sys
from html import unescape
from pathlib import Path
from xml.sax.saxutils import escape

PLUGIN_ROOT = Path(__file__).resolve().parents[1]
VIEW_FORMAT = 1
RENDERER = "dca_overview.py/1"
RULES = "1"
FONT_FAMILY = "Noto Sans CJK JP"
FONT_VERSION = "2.004"
FONT_STACK = "'Noto Sans CJK JP', 'Hiragino Sans', 'Yu Gothic', sans-serif"
VIEW_PATH = "docs/overview.view.json"
SVG_PATH = "docs/overview.svg"
METRICS_PATH = "docs/font-metrics.json"
VIEW_DATA_PATH = "skills/overview/references/view-data.md"
SOURCES_PATH = "skills/principles/references/dca/SOURCES.json"

KINDS = ("案内", "参照", "呼び出し", "引き継ぎ", "生成", "実行環境")
LINE_KINDS = ("案内", "実行環境")
BOX_KINDS = ("skill", "reference", "artifact", "host")
RELATION = re.compile(r"^- (案内|呼び出し|引き継ぎ|生成) (skill|artifact):([a-z-]+): (.+)$")
LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+)\)")
HOSTS = (
    ("host:claude-code", "Claude Code", ".claude-plugin/plugin.json", None),
    ("host:codex-cli", "Codex CLI", "plugin.json", "extensions.com.openai"),
)
UNICODE_BLOCKS = (
    (0x0020, 0x007E),
    (0x00A0, 0x00FF),
    (0x2000, 0x206F),
    (0x2190, 0x21FF),
    (0x2500, 0x257F),
    (0x25A0, 0x25FF),
    (0x3000, 0x303F),
    (0x3040, 0x30FF),
    (0x31F0, 0x31FF),
    (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF),
    (0xFF00, 0xFFEF),
)
NO_LINE_START = set("、。，．・：；！？）」』】〕〉》ーぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ々,.;:!?)]}%")
NO_LINE_END = set("（「『【〔〈《([{")
TOKEN = re.compile(r"[A-Za-z0-9_\-./:@#+*=<>~%$&|\\`]+|\s|.", re.S)

TEXT = "#142638"
MUTED = "#4a6075"
LINE = "#47647b"
CANVAS_WIDTH = 1480
MARGIN = 40
REGION_PAD = 24
BOX_PAD = 16
HEADING_HEIGHT = 48
GAP = 16


class OverviewError(Exception):
    pass


def digest_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def frontmatter(text):
    lines = text.split("\n")
    fields = {}
    if lines and lines[0] == "---":
        end = lines.index("---", 1)
        for line in lines[1:end]:
            key, separator, value = line.partition(": ")
            if separator:
                fields[key] = value
        return fields, "\n".join(lines[end + 1:])
    return fields, text


def split_sections(body):
    found = {}
    current = None
    for line in body.split("\n"):
        if line.startswith("## "):
            current = line[3:].strip()
            found[current] = []
        elif current is not None:
            found[current].append(line)
    return found


def first_heading(text):
    for line in text.split("\n"):
        if line.startswith("# "):
            return line[2:].strip()
    raise OverviewError("見出しがない")


def first_paragraph(text):
    lines = text.split("\n")
    seen_heading = False
    for line in lines:
        if line.startswith("# ") and not seen_heading:
            seen_heading = True
            continue
        if seen_heading and line.strip() and not line.startswith(("#", "-", "|", "```")):
            return LINK.sub(lambda match: match.group(1), line.strip())
    raise OverviewError("説明の段落がない")


def heading_texts(text):
    return {line.lstrip("#").strip() for line in text.split("\n") if line.startswith("#")}


def registered_skills(root):
    return sorted(item.name for item in (root / "skills").iterdir() if (item / "SKILL.md").is_file())


def reference_targets(root, name):
    skill_path = root / "skills" / name / "SKILL.md"
    _, body = frontmatter(skill_path.read_text(encoding="utf-8"))
    lines = split_sections(body).get("読む資料", [])
    targets = []
    for line in lines:
        if not line.startswith("- ["):
            continue
        for _, link in LINK.findall(line):
            resolved = (skill_path.parent / link.split("#")[0]).resolve()
            targets.append(resolved.relative_to(root.resolve()).as_posix())
    return targets


def input_paths(root):
    paths = {"plugin.json", ".claude-plugin/plugin.json", VIEW_DATA_PATH}
    for name in registered_skills(root):
        paths.add("skills/%s/SKILL.md" % name)
        metadata = root / "skills" / name / "agents" / "openai.yaml"
        if metadata.is_file():
            paths.add("skills/%s/agents/openai.yaml" % name)
        paths.update(reference_targets(root, name))
    if (root / SOURCES_PATH).is_file():
        paths.add(SOURCES_PATH)
    return sorted(paths)


def artifact_table(root):
    text = (root / VIEW_DATA_PATH).read_text(encoding="utf-8")
    rows = split_sections(text).get("成果物", [])
    artifacts = []
    for line in rows:
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) == 4 and cells[0].startswith("`"):
            artifacts.append(
                {
                    "id": cells[0].strip("`"),
                    "label": cells[1],
                    "storage": cells[2],
                    "summary": cells[3],
                }
            )
    return artifacts


def evidence(path, section):
    return [{"path": path, "section": section}]


def extract(root):
    root = Path(root)
    portable = json.loads((root / "plugin.json").read_text(encoding="utf-8"))
    claude = json.loads((root / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8"))
    if portable["name"] != claude["name"] or portable["version"] != claude["version"]:
        raise OverviewError("manifest の名称と版が一致しない")
    skills = registered_skills(root)
    nodes = []
    edges = []
    reference_ids = {}
    for name in skills:
        skill_rel = "skills/%s/SKILL.md" % name
        text = (root / skill_rel).read_text(encoding="utf-8")
        fields, body = frontmatter(text)
        heading = first_heading(body)
        metadata = root / "skills" / name / "agents" / "openai.yaml"
        implicit = bool(re.search(r"allow_implicit_invocation:\s*true", metadata.read_text(encoding="utf-8")))
        manual = fields.get("disable-model-invocation") == "true"
        nodes.append(
            {
                "id": "skill:%s" % name,
                "kind": "skill",
                "label": name,
                "summary": first_paragraph(body),
                "invocation": "manual" if manual and not implicit else "implicit",
                "evidence": evidence(skill_rel, heading),
            }
        )
        for position, target in enumerate(reference_targets(root, name)):
            reference_ids[target] = "reference:%s" % target
            edges.append(
                {
                    "id": "参照:skill:%s>reference:%s" % (name, target),
                    "kind": "参照",
                    "from": "skill:%s" % name,
                    "to": "reference:%s" % target,
                    "label": "読む資料",
                    "order": position,
                    "evidence": evidence(skill_rel, "読む資料"),
                }
            )
        declared = [
            match.groups()
            for match in (RELATION.match(line) for line in split_sections(body).get("他の Skill との関係", []))
            if match is not None
        ]
        for position, (kind, target_kind, target, label) in enumerate(declared):
            edges.append(
                {
                    "id": "%s:skill:%s>%s:%s" % (kind, name, target_kind, target),
                    "kind": kind,
                    "from": "skill:%s" % name,
                    "to": "%s:%s" % (target_kind, target),
                    "label": label,
                    "order": position,
                    "evidence": evidence(skill_rel, "他の Skill との関係"),
                }
            )
    bundled_count = 0
    if (root / SOURCES_PATH).is_file():
        bundled_count = len(json.loads((root / SOURCES_PATH).read_text(encoding="utf-8"))["files"])
    for target, node_id in sorted(reference_ids.items()):
        text = (root / target).read_text(encoding="utf-8")
        heading = first_heading(text)
        node = {
            "id": node_id,
            "kind": "reference",
            "label": heading,
            "summary": first_paragraph(text),
            "evidence": evidence(target, heading),
        }
        if target.endswith("references/index.md"):
            node["bundled_files"] = bundled_count
        nodes.append(node)
    for artifact in artifact_table(root):
        nodes.append(
            {
                "id": "artifact:%s" % artifact["id"],
                "kind": "artifact",
                "label": artifact["label"],
                "storage": artifact["storage"],
                "summary": artifact["summary"],
                "evidence": evidence(VIEW_DATA_PATH, "成果物"),
            }
        )
    nodes.append(
        {
            "id": "plugin:%s" % portable["name"],
            "kind": "plugin",
            "label": portable["name"],
            "summary": portable["description"],
            "evidence": evidence("plugin.json", None),
        }
    )
    for position, (node_id, label, manifest, section) in enumerate(HOSTS):
        nodes.append(
            {
                "id": node_id,
                "kind": "host",
                "label": label,
                "summary": "マニフェスト: %s" % manifest,
                "evidence": evidence(manifest, section),
            }
        )
        edges.append(
            {
                "id": "実行環境:plugin:%s>%s" % (portable["name"], node_id),
                "kind": "実行環境",
                "from": "plugin:%s" % portable["name"],
                "to": node_id,
                "label": "マニフェスト",
                "order": position,
                "evidence": evidence(manifest, section),
            }
        )
    metrics_path = root / METRICS_PATH
    view = {
        "format": VIEW_FORMAT,
        "plugin": {"name": portable["name"], "version": portable["version"]},
        "inputs": [{"path": path, "sha256": digest_file(root / path)} for path in input_paths(root)],
        "registration": {"skills": skills},
        "nodes": sorted(nodes, key=lambda item: item["id"]),
        "edges": sorted(edges, key=lambda item: item["id"]),
        "rendering": {
            "renderer": RENDERER,
            "rules": RULES,
            "font": "%s %s" % (FONT_FAMILY, FONT_VERSION),
            "metrics_sha256": digest_file(metrics_path) if metrics_path.is_file() else None,
        },
    }
    return view


def dump_view(view):
    return json.dumps(view, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


class Metrics:
    def __init__(self, document):
        font = document["font"]
        if font["family"] != FONT_FAMILY or FONT_VERSION not in font["version"]:
            raise OverviewError("測定値のフォントが描画条件と違う: %s %s" % (font["family"], font["version"]))
        self.units = font["units_per_em"]
        self.ranges = document["ranges"]
        self.starts = [item[0] for item in self.ranges]

    def advance(self, char):
        code = ord(char)
        position = bisect.bisect_right(self.starts, code) - 1
        if position >= 0:
            start, end, width = self.ranges[position]
            if start <= code <= end:
                return width
        raise OverviewError("フォントの測定値にない文字: U+%04X %s" % (code, char))

    def width(self, text, size):
        return math.ceil(sum(self.advance(char) for char in text) * size / self.units)


def load_metrics(root):
    path = Path(root) / METRICS_PATH
    if not path.is_file():
        raise OverviewError("%s がない。metrics で生成する" % METRICS_PATH)
    return Metrics(json.loads(path.read_text(encoding="utf-8")))


def read_u16(data, offset):
    return struct.unpack(">H", data[offset:offset + 2])[0]


def read_u32(data, offset):
    return struct.unpack(">I", data[offset:offset + 4])[0]


def font_tables(data):
    tables = {}
    for index in range(read_u16(data, 4)):
        tag, _, offset, length = struct.unpack(">4sIII", data[12 + 16 * index:28 + 16 * index])
        tables[tag.decode("latin-1")] = (offset, length)
    return tables


def font_name(data, tables, name_id):
    offset, _ = tables["name"]
    count = read_u16(data, offset + 2)
    storage = offset + read_u16(data, offset + 4)
    for index in range(count):
        record = offset + 6 + 12 * index
        platform, _, _, identifier, length, string_offset = struct.unpack(">HHHHHH", data[record:record + 12])
        if identifier == name_id and platform == 3:
            return data[storage + string_offset:storage + string_offset + length].decode("utf-16-be")
    raise OverviewError("フォントの name テーブルに %d がない" % name_id)


def font_groups(data, tables):
    offset, _ = tables["cmap"]
    for index in range(read_u16(data, offset + 2)):
        record = offset + 4 + 8 * index
        platform, encoding, subtable = struct.unpack(">HHI", data[record:record + 8])
        start = offset + subtable
        if platform == 3 and encoding == 10 and read_u16(data, start) == 12:
            groups = []
            for number in range(read_u32(data, start + 12)):
                base = start + 16 + 12 * number
                groups.append(struct.unpack(">III", data[base:base + 12]))
            return groups
    raise OverviewError("cmap に format 12 の Unicode 表がない")


def generate_metrics(font_path):
    data = Path(font_path).read_bytes()
    tables = font_tables(data)
    if "CFF " not in tables and "glyf" not in tables:
        raise OverviewError("OpenType のフォントではない")
    version = font_name(data, tables, 5)
    family = font_name(data, tables, 1)
    if FONT_VERSION not in version:
        raise OverviewError("フォントの版が %s ではない: %s" % (FONT_VERSION, version))
    units = read_u16(data, tables["head"][0] + 18)
    groups = font_groups(data, tables)
    starts = [item[0] for item in groups]
    hhea_offset = tables["hhea"][0]
    metric_count = read_u16(data, hhea_offset + 34)
    hmtx_offset = tables["hmtx"][0]

    def advance(glyph):
        index = min(glyph, metric_count - 1)
        return read_u16(data, hmtx_offset + 4 * index)

    ranges = []
    for first, last in UNICODE_BLOCKS:
        for code in range(first, last + 1):
            position = bisect.bisect_right(starts, code) - 1
            if position < 0:
                continue
            start, end, glyph_start = groups[position]
            if code > end:
                continue
            glyph = glyph_start + (code - start)
            if glyph == 0:
                continue
            width = advance(glyph)
            if ranges and ranges[-1][1] == code - 1 and ranges[-1][2] == width:
                ranges[-1][1] = code
            else:
                ranges.append([code, code, width])
    return {
        "font": {
            "family": family,
            "version": version,
            "units_per_em": units,
            "source_sha256": hashlib.sha256(data).hexdigest(),
        },
        "ranges": ranges,
    }


def tokenize(text):
    return TOKEN.findall(text)


def wrap(metrics, text, size, width):
    lines = []
    current = []
    current_width = 0
    for token in tokenize(text):
        token_width = metrics.width(token, size)
        if token_width > width:
            pieces = list(token)
        else:
            pieces = [token]
        for piece in pieces:
            piece_width = metrics.width(piece, size)
            if current and current_width + piece_width > width and not piece.isspace():
                carried = []
                if piece[0] in NO_LINE_START and len(current) > 1:
                    carried.append(current.pop())
                while current and current[-1][-1] in NO_LINE_END:
                    carried.insert(0, current.pop())
                lines.append("".join(current).rstrip())
                current = carried
                current_width = sum(metrics.width(item, size) for item in current)
            if current or not piece.isspace():
                current.append(piece)
                current_width += piece_width
    if current:
        lines.append("".join(current).rstrip())
    return [line for line in lines if line] or [""]


class Drawing:
    def __init__(self, metrics):
        self.metrics = metrics
        self.items = []

    def rect(self, x, y, width, height, fill, stroke, dash=None, radius=10, stroke_width=2, node=None):
        extra = ' stroke-dasharray="%s"' % dash if dash else ""
        marker = ' data-node="%s"' % escape(node) if node else ""
        self.items.append(
            '<rect x="%d" y="%d" width="%d" height="%d" rx="%d" fill="%s" stroke="%s" stroke-width="%d"%s%s/>'
            % (x, y, width, height, radius, fill, stroke, stroke_width, extra, marker)
        )

    def text(self, x, y, content, size, fill=TEXT, anchor="start"):
        self.items.append(
            '<text x="%d" y="%d" font-size="%d" fill="%s"%s>%s</text>'
            % (x, y, size, fill, "" if anchor == "start" else ' text-anchor="%s"' % anchor, escape(content))
        )

    def path(self, commands, dash=None, arrow=False, edge=None, bus=None):
        extra = ' stroke-dasharray="%s"' % dash if dash else ""
        marker = ' marker-end="url(#arrow)"' if arrow else ""
        label = ""
        if edge:
            label = ' data-edge="%s"' % escape(edge)
        elif bus:
            label = ' data-bus="%s"' % escape(bus)
        self.items.append(
            '<path d="%s" fill="none" stroke="%s" stroke-width="2"%s%s%s/>' % (commands, LINE, extra, marker, label)
        )


class Box:
    def __init__(self, metrics, width, parts):
        self.width = width
        self.lines = []
        inner = width - 2 * BOX_PAD
        for text, size, color, gap in parts:
            for number, line in enumerate(wrap(metrics, text, size, inner)):
                self.lines.append((line, size, color, gap if number == 0 else 2))
        self.height = BOX_PAD * 2 + self.content_height()

    def content_height(self):
        total = 0
        for index, (_, size, _, gap) in enumerate(self.lines):
            total += (gap if index else 0) + math.ceil(size * 1.45)
        return total

    def draw(self, drawing, x, y, height, style, node=None):
        drawing.rect(x, y, self.width, height, style["fill"], style["stroke"], style.get("dash"), node=node)
        cursor = y + BOX_PAD
        for index, (line, size, color, gap) in enumerate(self.lines):
            cursor += gap if index else 0
            drawing.text(x + BOX_PAD, cursor + math.ceil(size * 1.1), line, size, color)
            cursor += math.ceil(size * 1.45)


STYLES = {
    "skill": {"fill": "#eef5fb", "stroke": "#7ea0c0"},
    "skill-implicit": {"fill": "#fff4dc", "stroke": "#c8963c"},
    "skill-common": {"fill": "#e8f3ee", "stroke": "#5f9c82"},
    "reference": {"fill": "#ffffff", "stroke": "#5f9c82"},
    "host": {"fill": "#f1f3f5", "stroke": "#7b8794"},
    "artifact-consumer": {"fill": "#ffffff", "stroke": "#5f9c82", "dash": "8 5"},
    "artifact-maintenance": {"fill": "#fff9ec", "stroke": "#c8963c"},
}
INVOCATION_TEXT = {"manual": "利用者が明示して起動する", "implicit": "利用者の明示でも、依頼の内容からでも起動する"}
SKILL_COLUMNS = 4
HUB_WIDTH = 400


def display_names(view):
    return {
        node["id"]: node["label"]
        for node in view["nodes"]
        if node["kind"] in ("skill", "reference", "artifact", "host")
    }


def neighbors(view, node_id, kind, direction):
    key, other = ("from", "to") if direction == "out" else ("to", "from")
    return sorted(edge[other] for edge in view["edges"] if edge[key] == node_id and edge["kind"] == kind)


def short(name_map, ids):
    return "・".join(name_map[item] for item in ids)


def skill_parts(view, names, node):
    parts = [
        (node["label"], 24, TEXT, 0),
        (INVOCATION_TEXT[node["invocation"]], 13, MUTED, 2),
        (node["summary"], 15, TEXT, 8),
    ]
    references = neighbors(view, node["id"], "参照", "out")
    if references:
        parts.append(("参照: " + short(names, references), 14, MUTED, 8))
    handoffs = neighbors(view, node["id"], "引き継ぎ", "out")
    if handoffs:
        parts.append(("引き継ぎ先: " + short(names, handoffs), 14, MUTED, 4))
    products = neighbors(view, node["id"], "生成", "out")
    if products:
        parts.append(("生成: " + short(names, products), 14, MUTED, 4))
    return parts


def skill_style(node, view):
    if node["id"] == "skill:principles":
        return STYLES["skill-common"]
    if node["invocation"] == "implicit":
        return STYLES["skill-implicit"]
    return STYLES["skill"]


def row_layout(metrics, nodes_parts, total_width, columns, gap):
    width = int((total_width - gap * (columns - 1)) / columns)
    return [Box(metrics, width, parts) for parts in nodes_parts], width


def render(view, metrics):
    names = display_names(view)
    nodes = {node["id"]: node for node in view["nodes"]}
    skills = {node["label"]: node for node in view["nodes"] if node["kind"] == "skill"}
    drawing = Drawing(metrics)
    inner = CANVAS_WIDTH - 2 * MARGIN
    region_inner = inner - 2 * REGION_PAD
    cursor = MARGIN
    plugin = view["plugin"]
    drawing.text(MARGIN, cursor + 36, "dca Plugin の構成", 36)
    drawing.text(
        MARGIN,
        cursor + 74,
        "Skill %d 件、共通資料、実行環境、成果物の関係。版 %s" % (len(skills), plugin["version"]),
        18,
        MUTED,
    )
    cursor += 100

    guide_edges = sorted(
        (edge for edge in view["edges"] if edge["kind"] == "案内"),
        key=lambda edge: (edge["from"], edge["order"]),
    )
    hubs = sorted({edge["from"] for edge in guide_edges})
    if len(hubs) > 1:
        raise OverviewError("案内の起点が複数ある構成は、描画規則が対応していない: %s" % ", ".join(hubs))
    hub_id = hubs[0] if hubs else None
    skill_by_id = {node["id"]: node for node in skills.values()}
    targets = [edge["to"] for edge in guide_edges if edge["to"] in skill_by_id]
    others = sorted(
        (node["id"] for node in skills.values() if node["id"] != hub_id and node["id"] not in targets)
    )
    grid_ids = targets + others
    target_set = set(targets)
    skill_width = int((region_inner - GAP * (SKILL_COLUMNS - 1)) / SKILL_COLUMNS)
    grid_rows = [grid_ids[start:start + SKILL_COLUMNS] for start in range(0, len(grid_ids), SKILL_COLUMNS)]
    grid_boxes = [
        [Box(metrics, skill_width, skill_parts(view, names, skill_by_id[item])) for item in row] for row in grid_rows
    ]
    grid_heights = [max(box.height for box in row) for row in grid_boxes]
    hub_box = Box(metrics, HUB_WIDTH, skill_parts(view, names, skill_by_id[hub_id])) if hub_id else None
    hub_height = hub_box.height if hub_box else 0
    first_gap = 56 if hub_box else 0
    plugin_height = HEADING_HEIGHT + hub_height + first_gap + REGION_PAD
    row_gaps = []
    for index, row in enumerate(grid_rows):
        if index == 0:
            row_gaps.append(0)
        else:
            row_gaps.append(40 if any(item in target_set for item in row) else GAP)
    plugin_height += sum(grid_heights) + sum(row_gaps)
    drawing.rect(MARGIN, cursor, inner, plugin_height, "#fbfcfe", "#5b7fb0", radius=14, stroke_width=3)
    drawing.text(
        MARGIN + REGION_PAD,
        cursor + 32,
        "Plugin: dca（Skill %d 件。名前空間 dca:）" % len(skills),
        22,
    )
    region_x = MARGIN + REGION_PAD
    plugin_top = cursor
    plugin_bottom = cursor + plugin_height
    row_y = cursor + HEADING_HEIGHT
    hub_center = None
    hub_bottom = row_y
    if hub_box:
        hub_x = region_x + int((region_inner - HUB_WIDTH) / 2)
        hub_box.draw(drawing, hub_x, row_y, hub_height, skill_style(skill_by_id[hub_id], view), hub_id)
        hub_center = hub_x + int(HUB_WIDTH / 2)
        hub_bottom = row_y + hub_height
        row_y = hub_bottom + first_gap
    trunk_x = region_x - 12
    bus_levels = []
    positions = {}
    for index, (row, boxes, height) in enumerate(zip(grid_rows, grid_boxes, grid_heights)):
        row_y += row_gaps[index]
        for column, (item, box) in enumerate(zip(row, boxes)):
            x = region_x + column * (skill_width + GAP)
            box.draw(drawing, x, row_y, height, skill_style(skill_by_id[item], view), item)
            positions[item] = (x + int(skill_width / 2), row_y)
        if any(item in target_set for item in row):
            level = row_y - (28 if index == 0 else 20)
            bus_levels.append((level, [item for item in row if item in target_set]))
        row_y += height
    if hub_center is not None and bus_levels:
        first_level = bus_levels[0][0]
        drawing.path(
            "M %d %d L %d %d" % (hub_center, hub_bottom, hub_center, first_level), dash="7 5", bus="案内:" + hub_id
        )
        drawing.text(hub_center + 12, first_level - 6, "案内", 14, MUTED)
        multiple = len(bus_levels) > 1
        for level, items in bus_levels:
            xs = [positions[item][0] for item in items]
            left = min(xs + [hub_center]) if level == first_level else min(xs)
            if multiple:
                left = min(left, trunk_x)
            right = max(xs + ([hub_center] if level == first_level else []))
            drawing.path("M %d %d L %d %d" % (left, level, right, level), dash="7 5", bus="案内:" + hub_id)
            for item in items:
                center, top = positions[item]
                drawing.path(
                    "M %d %d L %d %d" % (center, level, center, top),
                    dash="7 5",
                    arrow=True,
                    edge="案内:%s>%s" % (hub_id, item),
                )
        if multiple:
            drawing.path(
                "M %d %d L %d %d" % (trunk_x, first_level, trunk_x, bus_levels[-1][0]),
                dash="7 5",
                bus="案内:" + hub_id,
            )
    cursor += plugin_height

    hosts = sorted((node for node in view["nodes"] if node["kind"] == "host"), key=lambda node: node["id"])
    host_edges = {edge["to"]: edge for edge in view["edges"] if edge["kind"] == "実行環境"}
    ordered_hosts = sorted(hosts, key=lambda node: host_edges.get(node["id"], {"order": 99})["order"])
    host_width = int((region_inner - GAP * (len(ordered_hosts) - 1)) / len(ordered_hosts)) if ordered_hosts else 0
    host_boxes = [
        Box(metrics, host_width, [(node["label"], 22, TEXT, 0), (node["summary"], 14, MUTED, 6)])
        for node in ordered_hosts
    ]
    host_height = max([box.height for box in host_boxes] or [0])
    host_region = HEADING_HEIGHT + host_height + REGION_PAD
    link = 52
    host_top = cursor + link
    for index, node in enumerate(ordered_hosts):
        edge = host_edges.get(node["id"])
        if edge is None:
            continue
        x = region_x + index * (host_width + GAP) + host_width - 40
        drawing.path(
            "M %d %d L %d %d" % (x, cursor, x, host_top), dash="2 5", arrow=True, edge=edge["id"]
        )
        drawing.text(x + 12, cursor + int(link / 2) + 5, "実行環境", 14, MUTED)
    cursor = host_top
    drawing.rect(MARGIN, cursor, inner, host_region, "#f8f9fa", "#7b8794", radius=14)
    drawing.text(
        MARGIN + REGION_PAD,
        cursor + 32,
        "実行環境（%s）" % "、".join(node["label"] for node in ordered_hosts),
        22,
    )
    for index, (node, box) in enumerate(zip(ordered_hosts, host_boxes)):
        box.draw(
            drawing,
            region_x + index * (host_width + GAP),
            cursor + HEADING_HEIGHT,
            host_height,
            STYLES["host"],
            node["id"],
        )
    cursor += host_region + 28

    references = [node for node in view["nodes"] if node["kind"] == "reference"]
    reference_parts = []
    for node in references:
        parts = [(node["label"], 18, TEXT, 0), (node["summary"], 14, TEXT, 6)]
        readers = neighbors(view, node["id"], "参照", "in")
        parts.append(("参照元: " + short(names, readers), 14, MUTED, 6))
        if node.get("bundled_files"):
            parts.append(("同梱思想 %d ファイル（出典 S01 から S06）" % node["bundled_files"], 14, MUTED, 4))
        reference_parts.append(parts)
    columns = 3
    reference_boxes, reference_width = row_layout(metrics, reference_parts, region_inner, columns, GAP)
    reference_rows = [reference_boxes[start:start + columns] for start in range(0, len(reference_boxes), columns)]
    row_heights = [max(box.height for box in row) for row in reference_rows]
    reference_region = HEADING_HEIGHT + sum(row_heights) + GAP * (len(row_heights) - 1) + REGION_PAD
    drawing.rect(MARGIN, cursor, inner, reference_region, "#f6fbf8", "#5f9c82", radius=14)
    drawing.text(MARGIN + REGION_PAD, cursor + 32, "共通資料と同梱思想（Skill が直接読む）", 22)
    row_y = cursor + HEADING_HEIGHT
    for row, height in zip(reference_rows, row_heights):
        for index, box in enumerate(row):
            box.draw(
                drawing,
                region_x + index * (reference_width + GAP),
                row_y,
                height,
                STYLES["reference"],
                references[reference_rows.index(row) * columns + index]["id"],
            )
        row_y += height + GAP
    cursor += reference_region + 28

    artifacts = [node for node in view["nodes"] if node["kind"] == "artifact"]
    artifact_parts = []
    for node in artifacts:
        parts = [
            (node["label"], 18, TEXT, 0),
            ("保存先の区分: " + node["storage"], 14, TEXT, 4),
            (node["summary"], 14, TEXT, 6),
        ]
        makers = neighbors(view, node["id"], "生成", "in")
        parts.append(("生成元: " + short(names, makers), 14, MUTED, 6))
        artifact_parts.append(parts)
    artifact_boxes, artifact_width = row_layout(metrics, artifact_parts, region_inner, 3, GAP)
    artifact_height = max(box.height for box in artifact_boxes)
    artifact_region = HEADING_HEIGHT + artifact_height + REGION_PAD
    drawing.rect(MARGIN, cursor, inner, artifact_region, "#fdfdfb", "#9a8f6a", radius=14)
    drawing.text(MARGIN + REGION_PAD, cursor + 32, "成果物（点線の枠は導入先の記録、実線の枠は Plugin の保守成果物）", 22)
    for index, (node, box) in enumerate(zip(artifacts, artifact_boxes)):
        style = STYLES["artifact-consumer"] if node["storage"] == "導入先の記録" else STYLES["artifact-maintenance"]
        box.draw(
            drawing,
            region_x + index * (artifact_width + GAP),
            cursor + HEADING_HEIGHT,
            artifact_height,
            style,
            node["id"],
        )
    cursor += artifact_region + 28

    legend = [
        "凡例",
        "矢印の線（破線）: 案内。Skill が利用者の目的に応じて別の Skill を案内する",
        "矢印の線（点線）: 実行環境。Plugin が対象の host で利用される",
        "Skill 内の「参照」: 「読む資料」の節にある共通資料や同梱思想を、判断の根拠として読む",
        "Skill 内の「引き継ぎ先」: 出力を、利用者を介して別の Skill に渡す",
        "Skill 内の「生成」: 成果物を作る",
        "色: 水色は手動起動の Skill、黄色は明示でも暗黙でも起動できる Skill、緑は共通思想。色だけでなく起動の文言も併記する",
    ]
    legend_lines = []
    for number, text in enumerate(legend):
        size = 20 if number == 0 else 15
        for line in wrap(metrics, text, size, region_inner):
            legend_lines.append((line, size))
    legend_height = REGION_PAD * 2 + sum(math.ceil(size * 1.5) for _, size in legend_lines)
    drawing.rect(MARGIN, cursor, inner, legend_height, "#ffffff", "#c3ccd6", radius=14)
    legend_y = cursor + REGION_PAD
    for line, size in legend_lines:
        legend_y += math.ceil(size * 1.5)
        drawing.text(region_x, legend_y - 4, line, size, TEXT if size > 15 else MUTED)
    cursor += legend_height + 24
    drawing.text(MARGIN, cursor + 14, "この図は scripts/dca_overview.py が view データから生成する。フォント: %s %s" % (FONT_FAMILY, FONT_VERSION), 13, MUTED)
    height = cursor + 14 + MARGIN
    title = "dca Plugin の構成図"
    description = "dca Plugin の Skill %d 件と、共通資料、実行環境、成果物の関係。版 %s。" % (len(skills), plugin["version"])
    header = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" role="img" aria-labelledby="title desc">\n'
        % (CANVAS_WIDTH, height, CANVAS_WIDTH, height)
    )
    defs = (
        '<defs><marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto">'
        '<path d="M 0 0 L 10 5 L 0 10 Z" fill="%s"/></marker></defs>\n' % LINE
    )
    style = '<style>text { font-family: %s; }</style>\n' % FONT_STACK
    background = '<rect width="%d" height="%d" fill="#f7f9fc"/>\n' % (CANVAS_WIDTH, height)
    body = "\n".join(drawing.items)
    return (
        header
        + "<title id=\"title\">%s</title>\n<desc id=\"desc\">%s</desc>\n" % (title, escape(description))
        + defs
        + style
        + background
        + body
        + "\n</svg>\n"
    )


def read_text_or_none(path):
    return path.read_text(encoding="utf-8") if path.is_file() else None


def check(root):
    root = Path(root)
    problems = []
    view_path = root / VIEW_PATH
    svg_path = root / SVG_PATH
    if not view_path.is_file() or not svg_path.is_file():
        return ["%s と %s が必要" % (VIEW_PATH, SVG_PATH)]
    stored_text = view_path.read_text(encoding="utf-8")
    stored = json.loads(stored_text)
    try:
        current = extract(root)
    except (OverviewError, KeyError, OSError, ValueError) as error:
        return ["view データを抽出できない: %s" % error]
    if stored.get("format") != VIEW_FORMAT:
        problems.append("view データの形式の版が違う")
    stored_inputs = {item["path"]: item["sha256"] for item in stored.get("inputs", [])}
    current_inputs = {item["path"]: item["sha256"] for item in current["inputs"]}
    for path in sorted(set(stored_inputs) | set(current_inputs)):
        if path not in current_inputs:
            problems.append("view データに、入力でなくなったファイルがある: %s" % path)
        elif path not in stored_inputs:
            problems.append("view データに、入力が記録されていない: %s" % path)
        elif stored_inputs[path] != current_inputs[path]:
            problems.append("入力が変わったが view データが追従していない: %s" % path)
    for path in current_inputs:
        if path.startswith("docs/") or "/results/" in path:
            problems.append("生成物を入力にしている: %s" % path)
    registered = registered_skills(root)
    stored_skills = sorted(node["label"] for node in stored.get("nodes", []) if node["kind"] == "skill")
    if stored_skills != registered or stored.get("registration", {}).get("skills") != registered:
        problems.append("図の Skill の集合が、登録された Skill と一致しない")
    ids = {node["id"] for node in stored.get("nodes", [])}
    for edge in stored.get("edges", []):
        if edge["from"] not in ids or edge["to"] not in ids:
            problems.append("線の端のノードがない: %s" % edge["id"])
    problems.extend(evidence_problems(root, stored))
    if dump_view(current) != stored_text:
        problems.append("view データが、現在の入力から抽出した内容と一致しない")
    try:
        rendered = render(stored, load_metrics(root))
    except OverviewError as error:
        problems.append("描画できない: %s" % error)
        return problems
    stored_svg = svg_path.read_text(encoding="utf-8")
    if rendered != stored_svg:
        problems.append("保持している SVG が、view データからの再描画と一致しない")
    if str(root.resolve()) in rendered or "file://" in rendered:
        problems.append("SVG に環境依存のパスが入っている")
    problems.extend(drawn_problems(stored, stored_svg))
    return problems


def drawn_problems(view, svg):
    problems = []
    drawn_edges = {unescape(item) for item in re.findall(r'data-edge="([^"]*)"', svg)}
    expected_edges = {edge["id"] for edge in view["edges"] if edge["kind"] in LINE_KINDS}
    for missing in sorted(expected_edges - drawn_edges):
        problems.append("view の関係が SVG に描かれていない: %s" % missing)
    for extra in sorted(drawn_edges - expected_edges):
        problems.append("view にない関係が SVG に描かれている: %s" % extra)
    drawn_nodes = {unescape(item) for item in re.findall(r'data-node="([^"]*)"', svg)}
    expected_nodes = {node["id"] for node in view["nodes"] if node["kind"] in BOX_KINDS}
    for missing in sorted(expected_nodes - drawn_nodes):
        problems.append("view のノードが SVG に描かれていない: %s" % missing)
    for extra in sorted(drawn_nodes - expected_nodes):
        problems.append("view にないノードが SVG に描かれている: %s" % extra)
    return problems


def evidence_problems(root, view):
    problems = []
    for item in view.get("nodes", []) + view.get("edges", []):
        for source in item.get("evidence", []):
            path = root / source["path"]
            if not path.is_file():
                problems.append("根拠のファイルがない: %s" % source["path"])
                continue
            section = source.get("section")
            if section and path.suffix == ".md" and section not in heading_texts(path.read_text(encoding="utf-8")):
                problems.append("根拠の節がない: %s の %s" % (source["path"], section))
    return problems


def write(path, text):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(prog="dca_overview")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("extract")
    commands.add_parser("render")
    commands.add_parser("check")
    metrics = commands.add_parser("metrics")
    metrics.add_argument("--font", required=True)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "extract":
            write(PLUGIN_ROOT / VIEW_PATH, dump_view(extract(PLUGIN_ROOT)))
        elif arguments.command == "render":
            view = json.loads((PLUGIN_ROOT / VIEW_PATH).read_text(encoding="utf-8"))
            write(PLUGIN_ROOT / SVG_PATH, render(view, load_metrics(PLUGIN_ROOT)))
        elif arguments.command == "metrics":
            document = generate_metrics(arguments.font)
            write(PLUGIN_ROOT / METRICS_PATH, json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n")
        else:
            problems = check(PLUGIN_ROOT)
            print(json.dumps({"problems": problems}, ensure_ascii=False, indent=2))
            return 1 if problems else 0
    except (OverviewError, OSError, ValueError, KeyError) as error:
        print(json.dumps({"status": "failed", "reason": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": "ok", "command": arguments.command}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
