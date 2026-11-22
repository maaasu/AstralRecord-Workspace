from collections import Counter, deque
from itertools import combinations
import json
from math import sqrt
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
SKILLTREE = ROOT / "40_filebase" / "35.features.skilltree"
DESIGN_PATH = Path(__file__).with_name("swordsman_design.json")
NEW_IDS = set(range(2661, 2901))
BASE_IDS = {1047, 1350, *range(2541, 2661)}
SKILL_SLOTS = {"SK1", "SK2"}

# 同じ FLAT 効果の既存ノードから、表示名・アイコン・タグをそのまま再利用する。
REUSED_PACKAGES = {
    "STR1": 1086,
    "AGI1": 1094,
    "VIT1": 1089,
    "ATK1": 1085,
    "DEF1": 1101,
    "MDEF1": 1103,
    "CR05": 1215,
    "SCR01": 1220,
    "SB025": 1267,
    "SH2": 1225,
    "ACC05": 1665,
    "NADMG05": 2547,
    "RES": 2637,
}

SMALL_DISPLAYS = {
    "HP5": ("&d灯芯の星脈", "RED_CANDLE", ("status", "resource", "health")),
    "MP5": ("&d湧水の星脈", "LIGHT_BLUE_DYE", ("status", "resource", "mana")),
    "ENG3": ("&d微風の星脈", "STRING", ("status", "resource", "energy")),
    "CD1": ("&d鋭刃の星環", "FLINT", ("status", "offense")),
    "SCD1": ("&d超星刃の星環", "AMETHYST_CLUSTER", ("status", "offense", "astral")),
    "SRR05": ("&b再起の星環", "NAUTILUS_SHELL", ("status", "defense", "shield")),
    "KB1": ("&d踏堅の星脈", "IRON_BOOTS", ("status", "defense", "durability")),
    "MELD1": ("&d鉄甲の星脈", "IRON_CHESTPLATE", ("status", "defense")),
}

SPECIAL_SLOTS = {
    "NI": "N1", "WL3": "N2", "WM3": "N3", "WH3": "N4",
    "O4": "N5", "O10": "N6", "KY": "K", "GL2": "G", "GH2": "G",
}

SPECIAL_DISPLAYS = {
    "NW": {
        "N1": ("研ぎ澄ます刃", "GRINDSTONE", ("strength", "offense")),
        "N2": ("会心の構え", "DIAMOND_SWORD", ("offense",)),
        "N3": ("斬鉄", "SHEARS", ("offense",)),
        "N4": ("見切り", "OBSERVER", ("accuracy", "agility")),
        "N5": ("必殺の一閃", "GOLDEN_SWORD", ("offense",)),
        "N6": ("剣士の矜持", "IRON_HELMET", ("strength", "health")),
        "K": ("捨て身の剣", "WITHER_SKELETON_SKULL", ("offense",)),
        "G": ("烈刃の道標", "RED_BANNER", ("strength", "offense")),
    },
    "NE": {
        "N1": ("踏み込み", "LEATHER_BOOTS", ("agility", "shield")),
        "N2": ("盾砕き", "STONE_AXE", ("shield", "offense")),
        "N3": ("超星の閃き", "FIREWORK_ROCKET", ("astral", "offense")),
        "N4": ("疾駆", "GOLDEN_BOOTS", ("agility", "energy")),
        "N5": ("連撃の心得", "IRON_CHAIN", ("offense", "accuracy")),
        "N6": ("闘気", "BLAZE_ROD", ("resource", "energy")),
        "K": ("破城の一撃", "TNT", ("shield", "astral")),
        "G": ("破盾の道標", "YELLOW_BANNER", ("shield", "agility")),
    },
    "SW": {
        "N1": ("盾の心得", "IRON_DOOR", ("shield", "defense")),
        "N2": ("堅牢の盾", "IRON_BLOCK", ("shield", "defense")),
        "N3": ("再起の盾", "ECHO_SHARD", ("shield", "defense")),
        "N4": ("魔を弾く盾", "AMETHYST_BLOCK", ("astral", "mana")),
        "N5": ("守護者の誓い", "LANTERN", ("shield", "defense")),
        "N6": ("鉄の意志", "CHAINMAIL_CHESTPLATE", ("durability", "shield")),
        "K": ("不落の盾", "CRYING_OBSIDIAN", ("shield", "defense")),
        "G": ("守護の道標", "BLUE_BANNER", ("shield", "mana")),
    },
    "SE": {
        "N1": ("頑強な体", "COOKED_BEEF", ("durability", "health")),
        "N2": ("鉄甲", "DIAMOND_CHESTPLATE", ("defense", "durability")),
        "N3": ("仁王立ち", "SMITHING_TABLE", ("defense", "durability")),
        "N4": ("三界の守り", "MAGMA_CREAM", ("defense", "element")),
        "N5": ("前線の盾", "STONE_BRICKS", ("health", "defense")),
        "N6": ("鋼の肉体", "DEEPSLATE", ("durability", "health")),
        "K": ("城塞化", "REINFORCED_DEEPSLATE", ("defense", "durability")),
        "G": ("城壁の道標", "GREEN_BANNER", ("durability", "element")),
    },
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, data):
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def make_effects(values):
    return [
        {"type": "status", "status": status, "modifierType": "FLAT", "value": value}
        for status, value in values.items()
    ]


def make_node(node_id, display, effects):
    name, icon, tags = display
    return {
        "$schema": "../schemas/node.v1.schema.json",
        "schemaVersion": 1,
        "nodeId": str(node_id),
        "name": name,
        "icon": icon,
        "tags": list(tags),
        "pointType": "CP",
        "pointCost": 1,
        "effects": effects,
        "unlockCondition": {"classId": "swordsman"},
    }


def load_small_displays(small_packages):
    displays = dict(SMALL_DISPLAYS)
    for package, node_id in REUSED_PACKAGES.items():
        node = read_json(SKILLTREE / "nodes" / f"{node_id}.json")
        expected = make_effects(small_packages[package])
        require(node["effects"] == expected, f"再利用元 {node_id} の効果が {package} と一致しない")
        displays[package] = (node["name"], node["icon"], node["tags"])
    return displays


def special_package(quadrant, assignment, values):
    name, icon, tags = SPECIAL_DISPLAYS[quadrant][assignment]
    prefix, kind = {"K": ("&c", "keystone"), "G": ("&b", "gateway")}.get(
        assignment, ("&6", "notable")
    )
    if (quadrant, assignment) == ("NE", "N4"):
        # 冒険者の「疾風の道標」と効果が重なるため、最大ENGだけ1下げる。
        require(values == {"AGILITY": 1, "MAX_ENERGY": 5}, "NE N4 の元の効果が変更されている")
        values = {**values, "MAX_ENERGY": 4}
    return (prefix + name, icon, ("status", kind, *tags)), values


def make_placement(node_id, coordinate, sx, sz):
    u, v = coordinate
    x, z = (round(value * 10) for value in (sx * u, sz * v))
    return {"nodeId": str(node_id), "x": block_coordinate(x), "y": 0, "z": block_coordinate(z)}


def block_coordinate(tenths):
    return tenths // 10 if tenths % 10 == 0 else tenths / 10


def build_swordsman(design):
    nodes = {}
    placements = []
    edges = []
    totals = {}
    routes = {}
    small_packages = design["smallPackages"]
    small_displays = load_small_displays(small_packages)

    for quadrant, layout in design["quadrants"].items():
        slots = {slot: layout["firstId"] + index for index, slot in enumerate(design["slotOrder"])}
        new_slots = set(slots)
        gates = {slot: int(node_id) for slot, node_id in layout["adventurerGate"].items()}
        skills = {slot: int(node_id) for slot, node_id in layout["skills"].items()}
        slots.update(skills)
        regular = Counter()
        keystone = Counter()

        for slot, node_id in slots.items():
            placements.append(make_placement(node_id, design["coords"][slot], layout["sx"], layout["sz"]))
            if slot not in new_slots:
                continue
            assignment = design["assign"][quadrant][slot]
            if slot in SPECIAL_SLOTS:
                require(assignment == SPECIAL_SLOTS[slot], f"{quadrant} {slot} の効果割り当てが異なる")
                display, values = special_package(quadrant, assignment, design["specialEffects"][quadrant][assignment])
            else:
                display, values = small_displays[assignment], small_packages[assignment]
            nodes[node_id] = make_node(node_id, display, make_effects(values))
            (keystone if assignment == "K" else regular).update(values)

        totals[quadrant] = (regular, keystone)
        targets = {slot: slots[slot] for slot in ("NI", "KY", "GL2", "GH2")}
        targets.update(skills)
        routes[quadrant] = (gates, set(slots.values()), targets)

        slots.update(gates)
        absent_skills = SKILL_SLOTS - skills.keys()
        edges.extend(
            tuple(sorted((slots[a], slots[b])))
            for a, b in design["edges"]
            if a not in absent_skills and b not in absent_skills
        )
    require(nodes.keys() == NEW_IDS, "新規ノード ID が 2661〜2900 と一致しない")
    return nodes, placements, edges, totals, routes


def merge_structure(structure, placements, edges, replaced_ids):
    base_placements = [node for node in structure["nodes"] if int(node["nodeId"]) not in replaced_ids]
    base_edges = [
        edge for edge in structure["edges"]
        if replaced_ids.isdisjoint((int(edge["sourceNodeId"]), int(edge["targetNodeId"])))
    ]
    require({int(node["nodeId"]) for node in base_placements} == BASE_IDS
            and len(base_placements) == 122 and len(base_edges) == 141,
            "冒険者・共通領域の配置または接続の数が異なる")
    new_edges = [{"sourceNodeId": str(a), "targetNodeId": str(b)} for a, b in edges]
    return {
        **structure,
        "nodes": sorted(base_placements + placements, key=lambda node: int(node["nodeId"])),
        "edges": sorted(base_edges + new_edges, key=lambda edge: (int(edge["sourceNodeId"]), int(edge["targetNodeId"]))),
    }


def orientation(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def segments_intersect(a, b, c, d):
    for axis in (0, 1):
        if max(a[axis], b[axis]) < min(c[axis], d[axis]) or max(c[axis], d[axis]) < min(a[axis], b[axis]):
            return False
    return orientation(a, b, c) * orientation(a, b, d) <= 0 and orientation(c, d, a) * orientation(c, d, b) <= 0


def distances_from(start, neighbors, allowed_ids):
    # 各到達先のコストは1CP。起点の購入済み道標は0CPとする。
    distances = {start: 0}
    queue = deque([start])
    while queue:
        node_id = queue.popleft()
        for neighbor in neighbors[node_id]:
            if neighbor in allowed_ids and neighbor not in distances:
                distances[neighbor] = distances[node_id] + 1
                queue.append(neighbor)
    return distances


def validate_structure(structure):
    placements = structure["nodes"]
    edges = [(int(edge["sourceNodeId"]), int(edge["targetNodeId"])) for edge in structure["edges"]]
    require(len(placements) == 367 and len(edges) == 426, "配置ノードまたは接続の数が異なる")
    require(all(node["y"] == 0 for node in placements), "y 座標が 0 以外のノードがある")
    # 0.1ブロック単位の整数で扱い、距離と交差を誤差なしで判定する。
    positions = {int(node["nodeId"]): (round(node["x"] * 10), round(node["z"] * 10)) for node in placements}
    require(len(positions) == len(placements), "配置ノード ID が重複している")
    require(len(set(positions.values())) == len(positions), "座標が重複している")
    require(len(set(edges)) == len(edges), "接続が重複している")
    require(edges == sorted(edges), "接続の並び順が不正")
    root = int(structure["rootNodeId"])
    require(root == 1047, "root ノード ID が異なる")

    neighbors = {node_id: [] for node_id in positions}
    for a, b in edges:
        require(a != b, f"自己接続 {a}")
        require(a < b, f"接続 {a}–{b} の端点順が不正")
        require(a in positions and b in positions, f"未配置ノードへの接続 {a}–{b}")
        neighbors[a].append(b)
        neighbors[b].append(a)
        if a != root:
            ax, az = positions[a]
            bx, bz = positions[b]
            require(ax * bx > 0 and az * bz > 0, f"接続 {a}–{b} が PP 予約通路を横切る")

    require(distances_from(root, neighbors, positions).keys() == positions.keys(),
            "root から到達できないノードがある")

    min_distance_squared = min(
        (ax - bx) ** 2 + (az - bz) ** 2
        for (ax, az), (bx, bz) in combinations(positions.values(), 2)
    )
    require(min_distance_squared >= 17 ** 2, "ノード間隔が 1.7 ブロック未満")
    for node_id, (x, z) in positions.items():
        if node_id != root:
            require(abs(x) >= 26 and abs(z) >= 26, f"ノード {node_id} が PP 予約通路に入っている")
    for (a, b), (c, d) in combinations(edges, 2):
        if {a, b}.isdisjoint((c, d)):
            require(not segments_intersect(positions[a], positions[b], positions[c], positions[d]),
                    f"接続 {a}–{b} と {c}–{d} が交差している")
    return neighbors, sqrt(min_distance_squared) / 10


def route_costs(routes, neighbors):
    costs = {}
    for quadrant, (gates, allowed_ids, targets) in routes.items():
        for gate, start in gates.items():
            distances = distances_from(start, neighbors, allowed_ids)
            require(set(targets.values()) <= distances.keys(),
                    f"{quadrant} {gate} から到達できない対象がある")
            costs[quadrant, gate] = {slot: distances[node_id] for slot, node_id in targets.items()}
    return costs


def print_totals(totals):
    print("| 象限 | 小ノード・主要ノード・道標の合計 | キーストーン単体 |")
    print("|:--|:--|:--|")
    for quadrant, (regular, keystone) in totals.items():
        regular_text = "、".join(f"{status} {value:+g}" for status, value in sorted(regular.items()))
        keystone_text = "、".join(f"{status} {value:+g}" for status, value in sorted(keystone.items()))
        print(f"| {quadrant} | {regular_text} | {keystone_text} |")


def print_route_costs(costs):
    print("冒険者の各道標から、同じ象限のソードマン領域を通る最短到達コスト（CP）")
    print("| 象限 | 起点 | NI | KY | GL2 | GH2 | SK1 | SK2 |")
    print("|:--|:--|--:|--:|--:|--:|--:|--:|")
    for (quadrant, gate), targets in costs.items():
        cells = " | ".join(str(targets.get(slot, "—")) for slot in ("NI", "KY", "GL2", "GH2", "SK1", "SK2"))
        print(f"| {quadrant} | {gate.removeprefix('a')} | {cells} |")


def main():
    design = read_json(DESIGN_PATH)
    skill_ids = [int(node_id) for layout in design["quadrants"].values() for node_id in layout["skills"].values()]
    require(len(skill_ids) == 5 and set(skill_ids) == {1202, 1203, 1204, 1352, 1361},
            "再利用するスキルノード ID が異なる")
    for node_id in skill_ids:
        node = read_json(SKILLTREE / "nodes" / f"{node_id}.json")
        require(node["pointType"] == "CP" and node["pointCost"] == 1, f"スキルノード {node_id} のコストが1CPではない")
    nodes, placements, edges, totals, routes = build_swordsman(design)

    status_path = ROOT / "40_filebase" / "75.shared.status" / "v1.status_types.yml"
    status_section = status_path.read_text(encoding="utf-8").split("\nstatuses:\n", 1)[1]
    known_statuses = set(re.findall(r"^  - \{ id: ([A-Z][A-Z0-9_]*)\s*,", status_section, re.MULTILINE))
    unknown_statuses = {effect["status"] for node in nodes.values() for effect in node["effects"]} - known_statuses
    require(not unknown_statuses, f"共有カタログにないステータス ID {sorted(unknown_statuses)}")

    structure_path = SKILLTREE / "structures" / "remake.json"
    structure = merge_structure(read_json(structure_path), placements, edges, NEW_IDS | set(skill_ids))
    neighbors, min_distance = validate_structure(structure)
    costs = route_costs(routes, neighbors)

    for node_id, node in nodes.items():
        write_json(SKILLTREE / "nodes" / f"{node_id}.json", node)
    write_json(structure_path, structure)

    print(f"検証成功　新規ノード {len(nodes)}、配置ノード {len(placements)} 個追加で計 {len(structure['nodes'])}、"
          f"接続 {len(edges)} 本追加で計 {len(structure['edges'])}")
    print("自己接続・重複接続・座標重複・接続の交差なし。全ノードが root から到達可能。")
    print(f"全ノード間の最小距離 {min_distance:.3f} ブロック。PP 予約通路への侵入なし。")
    print_totals(totals)
    print()
    print_route_costs(costs)


if __name__ == "__main__":
    main()
