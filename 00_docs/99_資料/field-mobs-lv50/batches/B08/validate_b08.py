#!/usr/bin/env python3
"""B08専用の読み取り専用マスター/参照/編集範囲検証。Plugin恒久テストではない。"""
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

BASE = "3444b74b2"
BATCH_PATH = "00_docs/99_資料/field-mobs-lv50/batches/B08"


class UniqueKeyLoader(yaml.SafeLoader):
    """同一mappingの二重キーを黙って上書きしない読込。"""


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
    """Mob loader同様、mappingは再帰継承、listは子の全置換として解決。"""
    result = copy.deepcopy(parent)
    for key, value in child.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def walk_refs(value):
    if isinstance(value, dict):
        if "ref" in value:
            yield value["ref"]
        for nested in value.values():
            yield from walk_refs(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from walk_refs(nested)


def namespace(relative, document):
    """共有configのnamespaceを優先し、item内のset_effectとloot pool/tableを分離。"""
    parts = relative.parts
    if parts[0] == "10.features.item":
        return "set" if "set_effect" in parts else "item"
    if parts[0] == "80.shared.loot":
        return "loot_" + parts[1] if len(parts) > 1 else "loot"
    return {
        "40.features.mob": "mob", "70.shared.buff": "buff", "30.features.skill": "skill",
        "60.features.world": "world", "41.features.mob.spawner": "mob_spawner",
    }.get(parts[0], parts[0])


def git(root, *args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", "-C", str(root), *args], encoding="utf-8").splitlines()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate(root):
    batch = root / BATCH_PATH
    manifest = json.loads((batch / "REGISTRATION_MANIFEST.json").read_text(encoding="utf-8"))
    require(manifest["base_commit"] == BASE and manifest["batch"] == "B08", "manifest基点/割当")
    allowed = manifest["owned_paths"]
    changes = set(git(root, "diff", "--name-only", BASE)) | set(git(root, "ls-files", "--others", "--exclude-standard"))
    require(all(any(path == p or p.endswith("/") and path.startswith(p) for p in allowed) for path in changes),
            "中央または他batchの編集: " + str(sorted(changes)))

    documents = {}
    ids = defaultdict(list)
    parse_errors = []
    paths = sorted((root / "40_filebase").rglob("*.yml"))
    for path in paths:
        try:
            doc = load(path)
        except yaml.YAMLError as error:
            parse_errors.append((str(path.relative_to(root)), str(error)))
            continue
        documents[path] = doc
        if isinstance(doc, dict) and doc.get("id"):
            space = namespace(path.relative_to(root / "40_filebase"), doc)
            ids[(space, str(doc["id"]))].append(str(path.relative_to(root)))
    require(not parse_errors, "YAML parse: " + str(parse_errors))
    duplicates = {str(key): value for key, value in ids.items() if len(value) > 1}
    require(not duplicates, "master ID重複: " + str(duplicates))
    require({master_id for space, master_id in ids if space == "item" and "10a00270" <= master_id <= "10a00279"}
            == {"10a00270", "10a00271", "10a00272"}, "B08素材予約枠の未割当使用")
    # カタログ内のstatus/tagはトップレベルmaster IDとは別のnamespace。
    statuses = documents[root / "40_filebase/75.shared.status/v1.status_types.yml"]["statuses"]
    status_ids = [entry["id"] for entry in statuses]
    tag_doc = documents[root / "40_filebase/76.shared.tag/v1.tags.yml"]
    tag_entries = tag_doc["tags"]
    tag_ids = [entry["id"] for entry in tag_entries]
    require(len(status_ids) == len(set(status_ids)), "共有status ID重複")
    require(len(tag_ids) == len(set(tag_ids)), "共有tag ID重複")

    result = {"yaml_files": len(paths), "master_ids": len(ids), "status_ids": len(status_ids),
              "tag_ids": len(tag_ids), "changed_paths": sorted(changes), "profiles": [], "references": [],
              "unregistered_skills": [], "unchanged_existing_drops": True}
    for entry in manifest["executors"]:
        mob_path = root / f"40_filebase/40.features.mob/enemy/v1.{entry['mob_id']}.yml"
        doc = documents[mob_path]
        require(doc["schemaVersion"] == 1 and doc["type"] == "MOB" and doc["category"] == "ENEMY", "mob schema")
        levels = [profile["level"] for profile in doc["levels"]]
        require(levels == entry["levels"] and len(levels) == 4 and len(set(levels)) == 4, "割当levels")
        require(doc["level"] == min(levels) and max(levels) <= 50 and 4 <= max(levels) - min(levels) <= 10,
                "レベル上限/最小/幅")
        require(doc["icon"] == doc["entityType"] + "_SPAWN_EGG", "mob icon")
        require(set(doc["tags"]) <= set(tag_ids), "タグ未解決")
        require(re.search(r"#\s+motif:", mob_path.read_text(encoding="utf-8")) is not None, "motif")
        inherited = {k: v for k, v in doc.items() if k != "levels"}
        prior_hp, prior_exp = 0, 0
        for profile in doc["levels"]:
            resolved = merge(inherited, profile)
            stats = {item["status"]: item["value"] for item in resolved["baseStats"]}
            require(len(stats) == len(resolved["baseStats"]) and set(stats) <= set(status_ids), "status未解決/重複")
            require(all(isinstance(value, (int, float)) and math.isfinite(value) and value > 0 for value in stats.values()),
                    "非有限/非正stats")
            require(stats["MAX_HEALTH"] > prior_hp and resolved["drops"]["exp"] > prior_exp, "個体成長")
            prior_hp, prior_exp = stats["MAX_HEALTH"], resolved["drops"]["exp"]
            combat = resolved["ai"]["combat"]
            require(combat["style"] == "MELEE" and combat["normalAttack"]["range"] == 2
                    and combat["normalAttack"]["intervalTicks"] == 36, "通常近接")
            require(combat["skills"] == [{"id": entry["implementation_id"], "activationRange": entry["activation_range"],
                    "cooldownTicks": entry["cooldown_ticks"], "castTimeTicks": 0}],
                    "skill object/timing")
            require("lootTable" not in resolved["drops"] and "money" not in resolved["drops"], "共有loot流用/Gold追加")
            drop = resolved["drops"]["items"]
            require(drop == [{"itemId": {"ref": "item:" + m["id"]}, "rate": m["rate"], "amount": 1,
                              "luckAffected": True, "hidden": False} for m in entry["materials"]], "素材dropの継承")
            for ref in walk_refs(resolved):
                space, master_id = ref.split(":", 1)
                require((space, master_id) in ids, "変更参照未解決: " + ref)
                result["references"].append(ref)
            result["profiles"].append({"mob_id": doc["id"], "level": profile["level"], "stats": stats,
                                       "exp": resolved["drops"]["exp"]})
        source_path = root / next(p for p in allowed if p.endswith(entry["class"].split(".")[-1] + ".java"))
        source = source_path.read_text(encoding="utf-8") + (root / manifest["support_class"]).read_text(encoding="utf-8")
        require(f'SKILL_ID = "{entry["implementation_id"]}"' in source, "executor ID")
        require(source_path.stem == entry["class"].split(".")[-1], "executor FQCN")
        require(all(token in source for token in ["handleMobDestroyed", "public void stop()", "MobState.COMBAT",
                "isMobCombatTarget", "canCastSkill", "canAttack", "DamageSource.SKILL", "scriptedAction(false)"]),
                "runtime guard/cleanup要素")
        require("spawnParticle(" not in source and "Particle." not in source and "sendMessage(" not in source,
                "共通描画/メッセージ境界")
        central = (root / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/AstralRecord.java").read_text(
            encoding="utf-8")
        class_name = entry["class"].split(".")[-1]
        registered_field = re.search(r"mobSkillRegistry\s*\.\s*register\s*\(\s*" + re.escape(entry["field"]) + r"\s*\)", central)
        registered_inline = re.search(r"mobSkillRegistry\s*\.\s*register\s*\(\s*new\s+" + re.escape(class_name) + r"\s*\(", central)
        if not (registered_field or registered_inline):
            result["unregistered_skills"].append(entry["implementation_id"])
    for path in changes:
        if path.endswith(".yml"):
            file = root / path
            require("#   motif:" in file.read_text(encoding="utf-8")
                    and "#   progression:" in file.read_text(encoding="utf-8"), "設計metadata")
    require(all(not p.startswith("40_filebase/") or p in allowed for p in changes), "既存filebase編集")
    support = (root / manifest["support_class"]).read_text(encoding="utf-8")
    require("ConditionApplyRequest" in support and "ConditionApplyReason.SKILL" in support, "既存状態異常API")
    require("casts.containsKey" in support and "points.size() > 40" in support, "重複cast拒否/点上限")
    require("getWorldBorder().isInside" in support and "isChunkLoaded" in support, "境界/chunk guard")
    require(all(token not in support for token in ["applyBuff(", "setFreezeTicks(", "addPotionEffect(", "runTaskTimerAsynchronously"]),
            "独自残留状態/非同期Bukkitを作らない")
    for entry in manifest["executors"]:
        code = (root / next(p for p in allowed if p.endswith(entry["class"].split(".")[-1] + ".java"))).read_text(encoding="utf-8")
        require("ConditionType.POISON, 70L" in code if "red_scorpion" in entry["mob_id"] else "ConditionType.BLINDNESS, 30L" in code,
                "有限の状態異常期限")
    for profile in result["profiles"]:
        if profile["mob_id"] == "field_red_scorpion":
            require(profile["stats"].get("SUPPORT_POWER", 0) > 0, "毒の基準能力不足")
    for entry in manifest["executors"]:
        for material in entry["materials"]:
            file = root / ids[("item", material["id"])][0]
            doc = documents[file]
            require(re.fullmatch(r"10[a-y][0-9]{5}", doc["id"]) is not None and doc["category"] == "material",
                    "素材ID/category")
            require(doc["name"] == material["name"] and doc["rarity"] == material["rarity"] and doc["maxStack"] == 64,
                    "素材name/rarity/stack")
            require(doc["saleValue"] == material["saleValue"] and not doc["unTradeable"] and not doc["unSellable"],
                    "素材売却経路")
    result["references"] = sorted(set(result["references"]))
    result["balance_projection"] = []
    for profile in result["profiles"]:
        i = (profile["level"] - 38) // 2
        player_r, player_hp, player_def = 5100 + 450 * i, 14300 + 1200 * i, 5270 + 460 * i
        stats = profile["stats"]
        defense = lambda d, r: max(0, 1 - (d / r) / 2.5) ** 1.35691545
        player_dps = player_r * defense(stats["DEFENSE"], player_r) * 1.30 * 0.92 * 1.60
        mob_dps = stats["ATTACK"] * defense(player_def, stats["ATTACK"]) * 0.915 / 1.8
        result["balance_projection"].append({"mob_id":profile["mob_id"],"level":profile["level"],
            "virtual_anchor_ttk_seconds": round(stats["MAX_HEALTH"] / player_dps, 2),
            "virtual_anchor_normal_melee_survival_seconds": round(player_hp / mob_dps, 2),
            "current_plus30_r416_58_damage_multiplier":round(defense(stats["DEFENSE"],416.58),4)})
    result["status"] = "passed_static_pending_registration_and_live_acceptance"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[5])
    args = parser.parse_args()
    print(json.dumps(validate(args.repo_root.resolve()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
