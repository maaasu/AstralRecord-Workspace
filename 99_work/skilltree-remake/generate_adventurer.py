from collections import Counter, deque
from itertools import combinations
import json
from math import sqrt
from pathlib import Path


SKILLTREE = Path(__file__).resolve().parents[2] / "40_filebase" / "35.features.skilltree"
ROOT_ID = 1047
JUST_DODGE_ID = 1350

# 座標は交差・距離判定のため 0.1 ブロックを 1 とする整数で保持する。
QUADRANTS = {
    "NW": (2541, -1, -1),
    "NE": (2571, +1, -1),
    "SW": (2601, -1, +1),
    "SE": (2631, +1, +1),
}

# スロット、正規座標 (u, v)、NW / NE / SW / SE のパッケージ。
SLOTS = (
    ("E", (27, 27), ("STR", "DEX", "INT", "VIT")),
    ("A1", (49, 28), ("HP", "HP", "HP", "STR")),
    ("B1", (68, 35), ("VIT", "VIT", "VIT", "DEX")),
    ("A2", (29, 49), ("ATK", "ATK", "MP", "INT")),
    ("B2", (35, 68), ("STR", "DEX", "INT", "HP")),
    ("N1", (64, 64), ("N1", "N1", "N1", "N1")),
    ("D1", (78, 78), ("NADMG", "ACC", "SKDMG", "RES")),
    ("D2", (92, 92), ("NADMG", "ACC", "SKDMG", "RES")),
    ("D3", (106, 106), ("NADMG", "ACC", "SKDMG", "RES")),
    ("K", (122, 122), ("K", "K", "K", "K")),
    ("C1L", (91, 35), ("VIT", "ENG", "MPR", "DEF")),
    ("W0L", (113, 47), ("DEF", "AGI", "MDEF", "STR")),
    ("W1L", (131, 36), ("HP", "ENG", "MP", "MDEF")),
    ("W2L", (148, 50), ("N2", "N2", "N2", "N2")),
    ("W3L", (139, 69), ("DEF", "VIT", "MDEF", "STR")),
    ("W4L", (118, 68), ("VIT", "HP", "VIT", "AGI")),
    ("ML", (105, 80), ("STR", "DEX", "INT", "DEX")),
    ("G1L", (153, 32), ("MDEF", "DEF", "DEF", "ENG")),
    ("G2L", (175, 32), ("MDEF", "DEF", "DEF", "ENG")),
    ("G3L", (197, 33), ("G", "G", "G", "G")),
    ("C1H", (35, 91), ("AGI", "ATK", "ATK", "INT")),
    ("W0H", (47, 113), ("STR", "DEX", "INT", "DEX")),
    ("W1H", (36, 131), ("AGI", "ATK", "MDEF", "INT")),
    ("W2H", (50, 148), ("N3", "N3", "N3", "N3")),
    ("W3H", (69, 139), ("AGI", "MDEF", "VIT", "AGI")),
    ("W4H", (68, 118), ("ENG", "MDEF", "HP", "AGI")),
    ("MH", (80, 105), ("ENG", "MP", "AGI", "ATK")),
    ("G1H", (32, 153), ("MP", "MP", "ENG", "MP")),
    ("G2H", (32, 175), ("MP", "MP", "ENG", "MP")),
    ("G3H", (33, 197), ("G", "G", "G", "G")),
)

# 各列の隣り合うスロットを接続する。
PATHS = (
    ("root", "E"),
    ("E", "A1", "B1", "N1", "B2", "A2", "E"),
    ("N1", "D1", "D2", "D3", "K"),
    ("B1", "C1L", "W0L", "W1L", "W2L", "W3L", "W4L", "W0L"),
    ("W1L", "G1L", "G2L", "G3L"),
    ("W4L", "ML", "D2"),
    ("B2", "C1H", "W0H", "W1H", "W2H", "W3H", "W4H", "W0H"),
    ("W1H", "G1H", "G2H", "G3H"),
    ("W4H", "MH", "D2"),
)

# 参照元 nodeId と期待する単一の FLAT 効果。
REUSED_PACKAGES = {
    "STR": (1086, "STRENGTH", 1),
    "DEX": (1093, "DEXTERITY", 1),
    "INT": (1107, "INTELLIGENCE", 1),
    "VIT": (1089, "VITALITY", 1),
    "AGI": (1094, "AGILITY", 1),
    "ATK": (1085, "ATTACK", 1),
    "DEF": (1101, "DEFENSE", 1),
    "MDEF": (1103, "MAGIC_DEFENSE", 1),
    "HP": (1100, "MAX_HEALTH", 10),
    "MP": (1108, "MAX_MANA", 10),
    "ENG": (1105, "MAX_ENERGY", 5),
    "MPR": (1308, "MP_REGEN", 1),
    "ACC": (1665, "ACCURACY", 0.5),
}

# 表示名、アイコン、タグ、FLAT 効果。
SMALL_PACKAGES = {
    "NADMG": ("&d剛撃の星脈", "STONE_SWORD", ("status", "offense", "strength"),
              (("NORMAL_ATTACK_DAMAGE_INCREASE", 0.5),)),
    "SKDMG": ("&d術理の星脈", "BOOK", ("status", "offense", "intelligence", "astral"),
              (("SKILL_DAMAGE_INCREASE", 0.5),)),
    "RES": ("&d三元の星脈", "PRISMARINE_SHARD", ("status", "defense", "element"),
            (("FIRE_RESISTANCE", 0.5), ("ICE_RESISTANCE", 0.5), ("LIGHTNING_RESISTANCE", 0.5))),
}

QUADRANT_PACKAGES = {
    "NW": {
        "N1": ("剛腕", "IRON_AXE", ("strength", "offense"),
               (("STRENGTH", 2), ("ATTACK", 1))),
        "N2": ("不動の構え", "SHIELD", ("strength", "defense", "durability"),
               (("MAX_HEALTH", 10), ("DEFENSE", 1), ("KNOCKBACK_RESISTANCE", 3))),
        "N3": ("重撃", "NETHERITE_SWORD", ("strength", "offense"),
               (("NORMAL_ATTACK_DAMAGE_INCREASE", 1.5), ("SHIELD_BREAK", 1))),
        "K": ("渾身", "NETHERITE_AXE", ("strength", "offense"),
              (("NORMAL_ATTACK_DAMAGE_INCREASE", 4), ("SHIELD_BREAK", 2), ("ATTACK_SPEED", -5))),
        "G": ("剛剣の道標", "LODESTONE", ("strength", "offense", "energy"),
              (("ATTACK", 1), ("MAX_ENERGY", 5))),
    },
    "NE": {
        "N1": ("鷹の目", "SPYGLASS", ("dexterity", "accuracy"),
               (("DEXTERITY", 2), ("ACCURACY", 1))),
        "N2": ("軽業", "PHANTOM_MEMBRANE", ("agility", "wind", "energy"),
               (("AGILITY", 2), ("ENERGY_REGEN", 1))),
        "N3": ("速射", "CROSSBOW", ("dexterity", "wind", "offense", "energy"),
               (("NORMAL_ATTACK_DAMAGE_INCREASE", 1), ("ENERGY_COST_REDUCTION", 1))),
        "K": ("一点集中", "SPECTRAL_ARROW", ("dexterity", "accuracy", "offense"),
              (("ACCURACY", 3), ("CRITICAL_RATE", 2), ("MAX_HEALTH", -15))),
        "G": ("疾風の道標", "RECOVERY_COMPASS", ("agility", "wind", "energy"),
              (("AGILITY", 1), ("MAX_ENERGY", 5))),
    },
    "SW": {
        "N1": ("叡智", "KNOWLEDGE_BOOK", ("intelligence", "astral", "mana"),
               (("INTELLIGENCE", 2), ("MAX_MANA", 10))),
        "N2": ("魔力循環", "CONDUIT", ("astral", "mana"),
               (("MP_REGEN", 1), ("MANA_COST_REDUCTION", 1), ("SUPPORT_POWER", 1))),
        "N3": ("術式強化", "WRITABLE_BOOK", ("intelligence", "astral", "offense"),
               (("SKILL_DAMAGE_INCREASE", 1.5), ("CAST_TIME_REDUCTION", 1))),
        "K": ("星詠みの代償", "DRAGON_BREATH", ("intelligence", "astral", "mana"),
              (("SKILL_DAMAGE_INCREASE", 3), ("MAX_MANA", 30), ("MAX_HEALTH", -20))),
        "G": ("星詠みの道標", "LECTERN", ("astral", "mana", "offense"),
              (("ATTACK", 1), ("MAX_MANA", 5))),
    },
    "SE": {
        "N1": ("頑健", "GOLDEN_APPLE", ("durability", "health"),
               (("VITALITY", 2), ("MAX_HEALTH", 10), ("HP_REGEN", 1))),
        "N2": ("鉄壁", "NETHERITE_CHESTPLATE", ("durability", "defense"),
               (("DEFENSE", 2), ("MAGIC_DEFENSE", 2))),
        "N3": ("星の加護", "TOTEM_OF_UNDYING", ("durability", "defense", "element"),
               (("FIRE_RESISTANCE", 1.5), ("ICE_RESISTANCE", 1.5),
                ("LIGHTNING_RESISTANCE", 1.5), ("NONE_RESISTANCE", 1))),
        "K": ("不屈", "RESPAWN_ANCHOR", ("durability", "defense", "health"),
              (("DAMAGE_REDUCTION_RATE", 3), ("MAX_HEALTH", 20), ("FINAL_DAMAGE_MULTIPLIER", -3))),
        "G": ("大樹の道標", "BELL", ("durability", "health", "offense"),
              (("ATTACK", 1), ("MAX_HEALTH", 5))),
    },
}

PACKAGE_TYPES = {
    "N1": ("&6", "notable"),
    "N2": ("&6", "notable"),
    "N3": ("&6", "notable"),
    "K": ("&c", "keystone"),
    "G": ("&b", "gateway"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def status_effects(values):
    return [
        {"type": "status", "status": status, "modifierType": "FLAT", "value": value}
        for status, value in values
    ]


def build_packages():
    shared = dict(SMALL_PACKAGES)
    for key, (node_id, status, value) in REUSED_PACKAGES.items():
        path = SKILLTREE / "nodes" / f"{node_id}.json"
        node = json.loads(path.read_text(encoding="utf-8"))
        values = ((status, value),)
        require(node["effects"] == status_effects(values), f"参照元 {node_id} の効果が仕様と異なる")
        shared[key] = (node["name"], node["icon"], node["tags"], values)

    packages = {}
    for quadrant, definitions in QUADRANT_PACKAGES.items():
        packages[quadrant] = dict(shared)
        for key, (name, icon, tags, values) in definitions.items():
            prefix, kind = PACKAGE_TYPES[key]
            packages[quadrant][key] = (prefix + name, icon, ("status", kind, *tags), values)
    return packages


def build_adventurer():
    packages = build_packages()
    nodes = {}
    positions = {ROOT_ID: (0, 0), JUST_DODGE_ID: (169, -60)}
    edges = []
    totals = {}
    targets = {}
    for column, (quadrant, (first_id, sx, sz)) in enumerate(QUADRANTS.items()):
        slot_ids = {"root": ROOT_ID}
        regular = Counter()
        keystone = Counter()
        for offset, (slot, (u, v), assignments) in enumerate(SLOTS):
            node_id = first_id + offset
            slot_ids[slot] = node_id
            positions[node_id] = (sx * u, sz * v)
            name, icon, tags, values = packages[quadrant][assignments[column]]
            nodes[node_id] = {
                "$schema": "../schemas/node.v1.schema.json",
                "schemaVersion": 1,
                "nodeId": str(node_id),
                "name": name,
                "icon": icon,
                "tags": list(tags),
                "pointType": "CP",
                "pointCost": 1,
                "effects": status_effects(values),
                "unlockCondition": {"classId": "adventurer"},
            }
            (keystone if slot == "K" else regular).update(dict(values))

        totals[quadrant] = (regular, keystone)
        targets[quadrant] = {slot: slot_ids[slot] for slot in ("N1", "K", "G3L", "G3H")}
        edges.extend((slot_ids[a], slot_ids[b]) for path in PATHS for a, b in zip(path, path[1:]))
        if quadrant == "NE":
            edges.append((JUST_DODGE_ID, slot_ids["W2L"]))

    edges = sorted((min(a, b), max(a, b)) for a, b in edges)
    return nodes, positions, edges, totals, targets


def orientation(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def segments_intersect(a, b, c, d):
    for axis in (0, 1):
        if max(a[axis], b[axis]) < min(c[axis], d[axis]) or max(c[axis], d[axis]) < min(a[axis], b[axis]):
            return False
    return orientation(a, b, c) * orientation(a, b, d) <= 0 and orientation(c, d, a) * orientation(c, d, b) <= 0


def validate(nodes, positions, edges, targets):
    require(set(nodes) == set(range(2541, 2661)), "新規ノード ID が 2541〜2660 と一致しない")
    require(len(positions) == 122 and len(edges) == 141, "配置ノードまたは接続の数が異なる")
    require(len(set(positions.values())) == len(positions), "座標が重複している")
    require(len(set(edges)) == len(edges), "接続が重複している")

    neighbors = {node_id: [] for node_id in positions}
    for a, b in edges:
        require(a != b, f"自己接続 {a}")
        require(a in positions and b in positions, f"未配置ノードへの接続 {a}–{b}")
        neighbors[a].append(b)
        neighbors[b].append(a)

    distance = {ROOT_ID: 0}
    queue = deque([ROOT_ID])
    while queue:
        node_id = queue.popleft()
        for neighbor in neighbors[node_id]:
            if neighbor not in distance:
                # 新規ノードと既存のジャスト回避はすべて 1CP。
                distance[neighbor] = distance[node_id] + 1
                queue.append(neighbor)
    require(distance.keys() == positions.keys(), "root から到達できないノードがある")

    adventurer_positions = {node_id: (x, z) for node_id, (x, z) in positions.items() if node_id != ROOT_ID}
    min_distance_squared = min(
        (x1 - x2) ** 2 + (z1 - z2) ** 2
        for (x1, z1), (x2, z2) in combinations(adventurer_positions.values(), 2)
    )
    require(min_distance_squared >= 17 ** 2, "ノード間隔が 1.7 ブロック未満")
    for node_id, (x, z) in adventurer_positions.items():
        require(abs(x) >= 26 and abs(z) >= 26, f"ノード {node_id} が PP 予約通路に入っている")
    for a, b in edges:
        if a != ROOT_ID:
            ax, az = positions[a]
            bx, bz = positions[b]
            require(ax * bx > 0 and az * bz > 0, f"接続 {a}–{b} が PP 予約通路を横切る")
    for (a, b), (c, d) in combinations(edges, 2):
        if {a, b}.isdisjoint((c, d)):
            require(not segments_intersect(positions[a], positions[b], positions[c], positions[d]),
                    f"接続 {a}–{b} と {c}–{d} が交差している")

    expected_costs = {"N1": 4, "K": 8, "G3L": 9, "G3H": 9}
    for quadrant, slots in targets.items():
        require({slot: distance[node_id] for slot, node_id in slots.items()} == expected_costs,
                f"{quadrant} の最短到達コストが仕様と異なる")
    return sqrt(min_distance_squared) / 10


def block_coordinate(tenths):
    if tenths % 10 == 0:
        return tenths // 10
    return tenths / 10


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def print_totals(totals):
    print("| 象限 | 小ノード・主要ノード・道標の合計 | キーストーン単体 |")
    print("|:--|:--|:--|")
    for quadrant, (regular, keystone) in totals.items():
        regular_text = "、".join(f"{status} {value:+g}" for status, value in sorted(regular.items()))
        keystone_text = "、".join(f"{status} {value:+g}" for status, value in sorted(keystone.items()))
        print(f"| {quadrant} | {regular_text} | {keystone_text} |")


def main():
    nodes, positions, edges, totals, targets = build_adventurer()
    min_distance = validate(nodes, positions, edges, targets)
    structure = {
        "$schema": "../schemas/structure.v1.schema.json",
        "schemaVersion": 1,
        "structureId": "remake",
        "name": "リメイクスキルツリー",
        "rootNodeId": str(ROOT_ID),
        "nodes": [
            {"nodeId": str(node_id), "x": block_coordinate(x), "y": 0, "z": block_coordinate(z)}
            for node_id, (x, z) in sorted(positions.items())
        ],
        "edges": [{"sourceNodeId": str(a), "targetNodeId": str(b)} for a, b in edges],
    }
    for node_id, node in nodes.items():
        write_json(SKILLTREE / "nodes" / f"{node_id}.json", node)
    write_json(SKILLTREE / "structures" / "remake.json", structure)

    print(f"検証成功　新規ノード {len(nodes)}、配置ノード {len(positions)}、接続 {len(edges)}")
    print("自己接続・重複接続・座標重複・接続の交差なし。全ノードが root から到達可能。")
    print(f"冒険者ノード間の最小距離 {min_distance:.3f} ブロック。PP 予約通路への侵入なし。")
    print("各象限の最短到達コストは内周の主要ノード 4CP、キーストーン 8CP、道標 9CP。")
    print_totals(totals)


if __name__ == "__main__":
    main()
