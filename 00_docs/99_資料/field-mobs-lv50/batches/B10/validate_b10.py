#!/usr/bin/env python3
"""B10のYAML・ID・参照・レベル継承と経済/戦闘の事前検算。個別Plugin恒久テストではない。"""
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

BATCH_PATH = "00_docs/99_資料/field-mobs-lv50/batches/B10"


class UniqueKeyLoader(yaml.SafeLoader):
    """重複mappingキーをエラーにするYAML reader。"""


def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise yaml.constructor.ConstructorError("duplicate key", node.start_mark, str(key), key_node.start_mark)
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)


def load(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueKeyLoader)


def merge(parent, child):
    result = copy.deepcopy(parent)
    for key, value in child.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
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
    return {"40.features.mob": "mob", "70.shared.buff": "buff", "30.features.skill": "skill",
            "60.features.world": "world", "41.features.mob.spawner": "mob_spawner"}.get(parts[0], parts[0])


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def git(root, *args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", "-C", str(root), *args],
                                   encoding="utf-8").splitlines()


def validate(root, integrated=False):
    manifest = json.loads((root / BATCH_PATH / "REGISTRATION_MANIFEST.json").read_text(encoding="utf-8"))
    allowed = manifest["owned_paths"]
    changed = set(git(root, "diff", "--name-only", manifest["base_commit"])) | set(
        git(root, "ls-files", "--others", "--exclude-standard"))
    owned = lambda path: any(path == p or p.endswith("/") and path.startswith(p) for p in allowed)
    if not integrated:
        require(all(owned(p) for p in changed), "B10所有範囲外の編集: " + str(sorted(changed)))
    docs, ids = {}, defaultdict(list)
    for path in sorted((root / "40_filebase").rglob("*.yml")):
        doc = load(path)
        docs[path] = doc
        if isinstance(doc, dict) and doc.get("id"):
            ids[(namespace(path.relative_to(root / "40_filebase")), str(doc["id"]))].append(path)
    duplicates = {str(k): [str(p.relative_to(root)) for p in v] for k, v in ids.items() if len(v) > 1}
    require(not duplicates, "master ID重複: " + str(duplicates))
    status_ids = {s["id"] for s in docs[root / "40_filebase/75.shared.status/v1.status_types.yml"]["statuses"]}
    tag_ids = {s["id"] for s in docs[root / "40_filebase/76.shared.tag/v1.tags.yml"]["tags"]}
    result = {"batch": "B10", "yaml_files": len(docs), "master_ids": len(ids), "profiles": [],
              "references": [], "registration_pending": [], "economy": [], "balance_projection": []}
    central = (root / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/AstralRecord.java").read_text(
        encoding="utf-8")
    for entry in manifest["executors"]:
        mob_path = root / f"40_filebase/40.features.mob/enemy/v1.{entry['mob_id']}.yml"
        mob = docs[mob_path]
        require(mob["schemaVersion"] == 1 and mob["id"] == entry["mob_id"] and mob["category"] == "ENEMY"
                and mob["type"] == "MOB", "Mob category/ID/schema")
        require(mob["icon"] == mob["entityType"] + "_SPAWN_EGG", "model/icon")
        require(set(mob["tags"]) <= tag_ids, "未定義tag")
        require([p["level"] for p in mob["levels"]] == entry["levels"] == [44, 46, 48, 50], "levels")
        require(mob["level"] == 44, "親levelは最小profile")
        require("#   motif:" in mob_path.read_text(encoding="utf-8")
                and "#   progression:" in mob_path.read_text(encoding="utf-8"), "design metadata")
        parent = {k: v for k, v in mob.items() if k != "levels"}
        previous_hp = previous_exp = 0
        for profile in mob["levels"]:
            resolved = merge(parent, profile)
            stats = {s["status"]: s["value"] for s in resolved["baseStats"]}
            require(len(stats) == len(resolved["baseStats"]) and set(stats) <= status_ids, "重複/未定義status")
            require(all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0 for v in stats.values()), "stat値")
            require("MAGIC_ATTACK" not in stats, "解決攻撃力をATTACKとMAGIC_ATTACKで二重にしない")
            require(stats["MAX_HEALTH"] > previous_hp and resolved["drops"]["exp"] > previous_exp, "HP/EXP成長")
            previous_hp, previous_exp = stats["MAX_HEALTH"], resolved["drops"]["exp"]
            combat = resolved["ai"]["combat"]
            require(combat["style"] == "MELEE" and combat["normalAttack"] == {
                "range": entry["normal_attack_range"], "intervalTicks": 40}, "通常攻撃")
            require(combat["skills"] == [{"id": entry["implementation_id"], "activationRange": entry["activation_range"],
                    "cooldownTicks": entry["cooldown_ticks"], "castTimeTicks": 0}], "専用skill object/timing")
            require("money" not in resolved["drops"] and "lootTable" not in resolved["drops"], "共有報酬追加なし")
            require(resolved["drops"]["items"] == [{"itemId": {"ref": "item:" + m["id"]}, "rate": m["rate"],
                    "amount": m["amount"], "luckAffected": m["luckAffected"], "hidden": m["hidden"]}
                    for m in entry["materials"]], "drop継承/manifest差")
            for ref in refs(resolved):
                space, master_id = ref.split(":", 1)
                require((space, master_id) in ids, "変更参照未解決: " + ref)
                result["references"].append(ref)
            result["profiles"].append({"mob_id": entry["mob_id"], "level": profile["level"], "stats": stats,
                                       "exp": resolved["drops"]["exp"]})
            # 正式Lv31+アンカーではなく、B07/B08比較用の仮想条件。
            i = (profile["level"] - 32) // 2
            player_r, player_hp, player_d = 3750 + 450 * i, 10700 + 1200 * i, 3890 + 460 * i
            defense = lambda d, r: max(0, 1 - (d / r) / 2.5) ** 1.35691545
            player_dps = player_r * defense(stats["DEFENSE"], player_r) * 1.30 * 0.92 * 1.60
            mob_dps = stats["ATTACK"] * defense(player_d, stats["ATTACK"]) * 0.915 / 2.0
            result["balance_projection"].append({"mob_id": entry["mob_id"], "level": profile["level"],
                "virtual_ttk_seconds": round(stats["MAX_HEALTH"] / player_dps, 2),
                "virtual_normal_melee_survival_seconds": round(player_hp / mob_dps, 2),
                "current_r416_58_defense_multiplier": defense(stats["DEFENSE"], 416.58)})
        source = root / next(p for p in allowed if p.endswith(entry["class"].split(".")[-1] + ".java"))
        require(f'SKILL_ID = "{entry["implementation_id"]}"' in source.read_text(encoding="utf-8"), "executor ID")
        if not re.search(r"mobSkillRegistry\s*\.\s*register\s*\(\s*" + re.escape(entry["field"]) + r"\s*\)", central):
            result["registration_pending"].append(entry["implementation_id"])
        expected_gold = 0.0
        for material in entry["materials"]:
            item_path = ids[("item", material["id"])][0]
            item = docs[item_path]
            require(re.fullmatch(r"10[a-y][0-9]{5}", item["id"]) and item["schemaVersion"] == 1
                    and item["category"] == "material", "item ID/schema/category")
            require(item["name"] == material["name"] and item["rarity"] == material["rarity"]
                    and item["saleValue"] == material["saleValue"] and item["maxStack"] == 64, "素材metadata")
            require(not item["unTradeable"] and not item["unSellable"], "素材売却/取引経路")
            require("#   motif:" in item_path.read_text(encoding="utf-8")
                    and "#   progression:" in item_path.read_text(encoding="utf-8"), "素材design metadata")
            require(0 < material["rate"] <= 100 and material["amount"] == 1, "drop確率/数量")
            expected_gold += material["rate"] / 100 * material["saleValue"]
        result["economy"].append({"mob_id": entry["mob_id"], "unmodified_sale_expected_gold": round(expected_gold, 2)})
    result["references"] = sorted(set(result["references"]))
    result["ultra_rare_comparison"] = {"drake_rate_percent": 0.15, "bison_integrated_rate_percent": 0.25,
        "drake_expected_kills": 100 / 0.15, "bison_expected_kills": 400, "rare_to_ultra_rate_ratio": 8 / 0.15,
        "note": "基礎確率、幸運補正前。drake頻度をbisonの約1.67倍と置く比較仮定で、実測ではない。"}
    result["owned_changed_paths"] = sorted(p for p in changed if owned(p))
    result["status"] = "passed_static_pending_integration_and_live_acceptance"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[5])
    parser.add_argument("--integrated", action="store_true", help="統合ツリーではB10以外のdiff所有監査だけを省略")
    args = parser.parse_args()
    print(json.dumps(validate(args.repo_root.resolve(), args.integrated), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
