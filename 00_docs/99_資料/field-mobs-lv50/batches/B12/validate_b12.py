#!/usr/bin/env python3
"""B12のマスター・参照・継承と比較用戦闘/経済試算。個別Mob恒久テストではない。"""
from __future__ import annotations

import argparse
import copy
import json
import math
import re
import subprocess
from collections import defaultdict
from pathlib import Path

import yaml

BATCH_PATH = "00_docs/99_資料/field-mobs-lv50/batches/B12"


class UniqueKeyLoader(yaml.SafeLoader):
    """重複mappingキーを拒否するreader。"""


def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise yaml.constructor.ConstructorError("duplicate key", node.start_mark, str(key), key_node.start_mark)
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)


def merge(parent, child):
    result = copy.deepcopy(parent)
    for key, value in child.items():
        result[key] = merge(result[key], value) if isinstance(value, dict) and isinstance(result.get(key), dict) else copy.deepcopy(value)
    return result


def refs(value):
    if isinstance(value, dict):
        if "ref" in value:
            yield value["ref"]
        for nested in value.values():
            yield from refs(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from refs(nested)


def namespace(relative):
    parts = relative.parts
    if parts[0] == "10.features.item":
        return "set" if "set_effect" in parts else "item"
    if parts[0] == "80.shared.loot":
        return "loot_" + parts[1]
    return {"40.features.mob": "mob", "70.shared.buff": "buff", "30.features.skill": "skill", "60.features.world": "world"}.get(parts[0], parts[0])


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def git(root, *args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", "-C", str(root), *args], encoding="utf-8").splitlines()


def validate(root, integrated=False):
    manifest = json.loads((root / BATCH_PATH / "REGISTRATION_MANIFEST.json").read_text(encoding="utf-8"))
    allowed = manifest["owned_paths"]
    changed = set(git(root, "diff", "--name-only", manifest["base_commit"])) | set(git(root, "ls-files", "--others", "--exclude-standard"))
    owned = lambda p: any(p == a or a.endswith("/") and p.startswith(a) for a in allowed)
    if not integrated:
        require(all(owned(p) for p in changed), "B12所有範囲外の編集: " + str(sorted(changed)))
    docs, ids = {}, defaultdict(list)
    for path in sorted((root / "40_filebase").rglob("*.yml")):
        doc = yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueKeyLoader)
        docs[path] = doc
        if isinstance(doc, dict) and doc.get("id"):
            ids[(namespace(path.relative_to(root / "40_filebase")), str(doc["id"]))].append(path)
    require(all(len(paths) == 1 for paths in ids.values()), "全体master ID重複")
    status_ids = {s["id"] for s in docs[root / "40_filebase/75.shared.status/v1.status_types.yml"]["statuses"]}
    tag_ids = {s["id"] for s in docs[root / "40_filebase/76.shared.tag/v1.tags.yml"]["tags"] if "MOB" in s["appliesTo"]}
    entry = manifest["executors"][0]
    require('catch (RuntimeException exception)' in entry["disable_code"]
            and 'shadeHedgehogStopFailure = exception;' in entry["disable_code"]
            and 'throw' not in entry["disable_code"]
            and entry["disable_after_cleanup_code"] == 'if (shadeHedgehogStopFailure != null) throw shadeHedgehogStopFailure;',
            "停止例外は全cleanup後まで保持する統合契約")
    mob_path = root / f"40_filebase/40.features.mob/enemy/v1.{entry['mob_id']}.yml"
    mob = docs[mob_path]
    require(mob["schemaVersion"] == 1 and mob["id"] == entry["mob_id"] and mob["type"] == "MOB" and mob["category"] == "ENEMY", "Mob必須項目")
    require(mob["entityType"] == "ARMADILLO" and mob["icon"] == "ARMADILLO_SPAWN_EGG" and mob["variant"] == {"age": "ADULT", "scale": 0.9}, "Mob model/icon")
    require(set(mob["tags"]) <= tag_ids, "未定義/対象外tag")
    require(mob["level"] == 36 and [p["level"] for p in mob["levels"]] == entry["levels"] == [36, 38, 40, 42], "level profile")
    result = {"batch": "B12", "yaml_files": len(docs), "master_ids": len(ids), "profiles": [], "references": [], "balance_projection": []}
    parent = {k: v for k, v in mob.items() if k != "levels"}
    previous = (0, 0, 0, 0)
    material = entry["materials"][0]
    expected_drop = {"itemId": {"ref": "item:10a00410"}, "rate": 35.0, "amount": 1, "luckAffected": True, "hidden": False}
    defense = lambda d, r: max(0, 1 - d / r / 2.5) ** 1.35691545
    for profile in mob["levels"]:
        effective = merge(parent, profile)
        stats = {s["status"]: s["value"] for s in effective["baseStats"]}
        require(len(stats) == len(effective["baseStats"]) and set(stats) <= status_ids, "重複/未定義status")
        require(all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0 for v in stats.values()), "status値域")
        require(set(stats) == {"MAX_HEALTH", "ATTACK", "DEFENSE", "ACCURACY", "EVASION", "MOVEMENT_SPEED"}, "未指定の追加戦闘補正")
        growth = (stats["MAX_HEALTH"], stats["ATTACK"], stats["DEFENSE"], effective["drops"]["exp"])
        require(all(current > old for current, old in zip(growth, previous)), "profile成長")
        previous = growth
        combat = effective["ai"]["combat"]
        require(combat["normalAttack"] == {"range": 2, "intervalTicks": 40} and combat["style"] == "MELEE", "normal attack")
        require(combat["skills"] == [{"id": entry["implementation_id"], "activationRange": 3, "cooldownTicks": 200, "castTimeTicks": 0}], "専用技/timing")
        require(effective["drops"]["items"] == [expected_drop] and set(effective["drops"]) == {"exp", "items", "lootTable"}, "単一通常素材drop/継承")
        require(effective["drops"]["lootTable"] == {"ref": "loot_table:" + entry["loot_table_id"]}, "Lv15以上共通報酬/継承")
        for ref in refs(effective):
            space, master_id = ref.split(":", 1)
            require((space, master_id) in ids, "変更参照未解決: " + ref)
            result["references"].append(ref)
        result["profiles"].append({"level": profile["level"], "stats": stats, "exp": effective["drops"]["exp"]})
        # 先行バッチと同じ比較条件。正式Lv31+アンカーまたは実測値ではない。
        index = (profile["level"] - 32) // 2
        player_r, player_hp, player_d = 3750 + 450 * index, 10700 + 1200 * index, 3890 + 460 * index
        player_dps = player_r * defense(stats["DEFENSE"], player_r) * 1.3 * 0.92 * 1.6
        pose_dps = player_r * defense(stats["DEFENSE"] * 1.35, player_r) * 1.3 * 0.92 * 1.6
        normal_hit = stats["ATTACK"] * defense(player_d, stats["ATTACK"])
        needle_hit = normal_hit * 0.95
        mob_dps = normal_hit * 0.915 / 2 * 0.75 + needle_hit * 0.915 / 10
        result["balance_projection"].append({"level": profile["level"], "virtual_player_r_hp_d": [player_r, player_hp, player_d],
            "virtual_ttk_base_seconds": round(stats["MAX_HEALTH"] / player_dps, 2),
            "virtual_ttk_first_pose_seconds": round(stats["MAX_HEALTH"] / player_dps + 1.5 * (1 - pose_dps / player_dps), 2),
            "virtual_survival_all_needles_hit_seconds": round(player_hp / mob_dps, 2),
            "virtual_needle_hp_percent": round(needle_hit / player_hp * 100, 2),
            "virtual_needle_zero_defense_hp_percent": round(stats["ATTACK"] * 0.95 / player_hp * 100, 2),
            "current_r416_58_defense_multiplier": defense(stats["DEFENSE"], 416.58)})
    item = docs[ids[("item", "10a00410")][0]]
    require(re.fullmatch(r"10[a-y][0-9]{5}", item["id"]) and item["schemaVersion"] == 1 and item["category"] == "material", "item schema/ID")
    require(item["name"] == material["name"] and item["rarity"] == material["rarity"] == "COMMON"
            and item["saleValue"] == material["saleValue"] == 540 and item["maxStack"] == 64
            and item["icon"] == "POINTED_DRIPSTONE" and not item["unTradeable"] and not item["unSellable"], "素材metadata/売却経路")
    for path in [mob_path, ids[("item", "10a00410")][0]]:
        text = path.read_text(encoding="utf-8")
        require("#   motif:" in text and "#   progression: 36" in text, "design metadata")
    source = root / allowed[0]
    require(f'SKILL_ID = "{entry["implementation_id"]}"' in source.read_text(encoding="utf-8"), "executor ID")
    central = (root / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/AstralRecord.java").read_text(encoding="utf-8")
    registered = bool(re.search(r"mobSkillRegistry\s*\.\s*register\s*\(\s*" + re.escape(entry["field"]) + r"\s*\)", central))
    result["registration_pending"] = [] if registered else [entry["implementation_id"]]
    result["references"] = sorted(set(result["references"]))
    # 新規参照の共通Loot tableからpool・itemまで追跡する。
    pending, visited = list(result["references"]), set()
    while pending:
        ref = pending.pop()
        if ref in visited:
            continue
        visited.add(ref)
        space, master_id = ref.split(":", 1)
        require((space, master_id) in ids, "間接参照未解決: " + ref)
        pending.extend(refs(docs[ids[(space, master_id)][0]]))
    result["transitive_references"] = sorted(visited)
    result["unmodified_sale_expected_gold"] = 540 * 0.35
    result["owned_changed_paths"] = sorted(p for p in changed if owned(p))
    result["status"] = "passed_static_pending_integration_and_live_acceptance"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[5])
    parser.add_argument("--integrated", action="store_true", help="統合時はB12以外のdiff所有監査だけを省略")
    args = parser.parse_args()
    print(json.dumps(validate(args.repo_root.resolve(), args.integrated), ensure_ascii=False, indent=2))
