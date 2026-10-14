"""E01専用: 基点との実効ドロップ比較。PyYAMLのみで実行、buildはしない。"""

from collections import defaultdict
from copy import deepcopy
import csv
import json
from pathlib import Path
import re
import subprocess

import yaml


BASE = "2c419c1af8cff4ffdef63a8cf726f96a3408fe63"
HERE = Path(__file__).resolve().parent
ROOT = Path(subprocess.check_output(
    ["git", "rev-parse", "--show-toplevel"], cwd=HERE, text=True,
).strip())


class UniqueKeyLoader(yaml.SafeLoader):
    """通常のYAML解析に、同一Mapのキー重複検出を加える。"""


def unique_mapping(loader, node, deep=False):
    loader.flatten_mapping(node)
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate YAML key: {key!r}, line {key_node.start_mark.line + 1}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping,
)


def load(text):
    return yaml.load(text, Loader=UniqueKeyLoader)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def merge(parent, override):
    # MobRepository.mergeLevelProfile/mergeJsonObjects: Mapは再帰、配列・scalarは置換。
    result = deepcopy(parent)
    for key, value in override.items():
        if isinstance(result.get(key), dict) and isinstance(value, dict):
            result[key] = merge(result[key], value)
        else:
            result[key] = deepcopy(value)
    return result


def without_new_drop(current, before, entry):
    result = deepcopy(current)
    items = result["drops"].get("items", [])
    check(items.count(entry) == 1, "new drop must appear exactly once")
    items.remove(entry)
    if "items" not in before["drops"]:
        check(not items, "unexpected remaining drops")
        result["drops"].pop("items")
    return result


def validate_material(row, material, source):
    allowed = {"schemaVersion", "id", "category", "name", "icon", "rarity",
               "maxStack", "saleValue", "lore", "unTradeable", "unSellable"}
    check(set(material) == allowed, "unexpected/missing material keys")
    check(type(material["schemaVersion"]) is int and material["schemaVersion"] == 1,
          "invalid schemaVersion")
    check(material["id"] == row["item_id"] and re.fullmatch(r"10[a-y]\d{5}", material["id"]),
          "invalid material id")
    check(material["category"] == "material" and material["rarity"] == "COMMON",
          "invalid category/rarity")
    check(re.sub(r"&[0-9a-fk-or]", "", material["name"]) == row["name"], "name differs from assignment")
    expected_icon = {"10a00300": "OAK_PLANKS", "10a00301": "GREEN_WOOL"}[row["item_id"]]
    check(material["icon"] == expected_icon, "invalid icon")
    check(type(material["maxStack"]) is int and material["maxStack"] == 64, "invalid maxStack")
    check(type(material["saleValue"]) is int and material["saleValue"] == 0, "invalid saleValue")
    check(type(material["unTradeable"]) is bool and material["unTradeable"] is False,
          "invalid unTradeable")
    check(type(material["unSellable"]) is bool and material["unSellable"] is False,
          "invalid unSellable")
    check(isinstance(material["lore"], list) and material["lore"] and
          all(isinstance(line, str) and line for line in material["lore"]), "invalid lore")
    check(source.startswith("# design:\n#   motif: ") and
          f"#   progression: {row['progression']}\n" in source, "missing design metadata")


def main():
    assignment_path = ROOT / "00_docs/99_資料/field-mobs-lv50/EXISTING_MATERIAL_ASSIGNMENTS.csv"
    with assignment_path.open(encoding="utf-8-sig", newline="") as stream:
        rows = [row for row in csv.DictReader(stream) if row["batch"] == "E01"]
    expected_assignments = {
        ("eriva_bark_sentinel", "10a00300",
         "40_filebase/40.features.mob/enemy/v1.eriva_bark_sentinel.yml",
         "40_filebase/10.features.item/10.material/v1.10a00300.eriva_bark_sentinel_material.yml"),
        ("eriva_camouflage_zombie", "10a00301",
         "40_filebase/40.features.mob/enemy/v1.eriva_camouflage_zombie.yml",
         "40_filebase/10.features.item/10.material/v1.10a00301.eriva_camouflage_zombie_material.yml"),
    }
    actual_assignments = {
        tuple(row[key] for key in ("mob_id", "item_id", "enemy_path", "material_path"))
        for row in rows
    }
    check(len(rows) == 2 and actual_assignments == expected_assignments, "unexpected E01 assignments")

    # 全filebaseの解析・論理マスタ(top-level id)の重複検出。ネストした参照idはマスタ定義ではない。
    definitions = defaultdict(list)
    documents = {}
    files = sorted((ROOT / "40_filebase").rglob("*.yml"))
    for path in files:
        relative = path.relative_to(ROOT).as_posix()
        document = load(path.read_text(encoding="utf-8-sig"))
        documents[relative] = document
        if isinstance(document, dict) and "id" in document:
            definitions[str(document["id"])].append(relative)
    duplicates = {key: value for key, value in definitions.items() if len(value) > 1}
    for master_id, paths in duplicates.items():
        namespaces = [Path(path).parts[1] for path in paths]
        check(len(namespaces) == len(set(namespaces)), f"duplicate master id in same namespace: {master_id}")
        # skill/buff、fishing area/worldで既存の同名IDを使う。基点にある組だけを許容する。
        for path in paths:
            original = load(subprocess.check_output(
                ["git", "show", f"{BASE}:{path}"], cwd=ROOT,
            ).decode("utf-8-sig"))
            check(str(original["id"]) == master_id, f"introduced cross-namespace duplicate: {master_id}")

    results = []
    for row in rows:
        before_text = subprocess.check_output(
            ["git", "show", f"{BASE}:{row['enemy_path']}"], cwd=ROOT,
        ).decode("utf-8-sig")
        before = load(before_text)
        current = documents[row["enemy_path"]]
        check(before["id"] == current["id"] == row["mob_id"], "mob id mismatch")
        check(f"#   progression: {row['progression']}\n" in before_text, "mob progression mismatch")
        check(definitions[row["item_id"]] == [row["material_path"]], "material path/id mismatch")
        material = documents[row["material_path"]]
        validate_material(row, material, (ROOT / row["material_path"]).read_text(encoding="utf-8-sig"))
        entry = {"itemId": {"ref": f"item:{row['item_id']}"}, "rate": 35.0,
                 "amount": 1, "luckAffected": True, "hidden": False}
        added = current["drops"]["items"][-1]
        check(added == entry and type(added["rate"]) is float and
              type(added["amount"]) is int and type(added["luckAffected"]) is bool and
              type(added["hidden"]) is bool, "new drop differs from documented contract")
        check(float(row["rate_percent"]) == entry["rate"] and int(row["amount"]) == entry["amount"]
              and row["luckAffected"] == "true" and row["hidden"] == "false"
              and row["rarity"] == "COMMON", "CSV drop contract mismatch")

        # 生YAMLの実データも、新規1件だけを除くと基点と完全一致することを確認。
        check(without_new_drop(current, before, entry) == before, "existing raw mob data changed")
        before_profiles = before.get("levels") or [{"level": before["level"]}]
        current_profiles = current.get("levels") or [{"level": current["level"]}]
        check(before_profiles == current_profiles, "level profiles changed")
        for old_profile, new_profile in zip(before_profiles, current_profiles, strict=True):
            old_effective = merge({k: v for k, v in before.items() if k != "levels"}, old_profile)
            new_effective = merge({k: v for k, v in current.items() if k != "levels"}, new_profile)
            check(without_new_drop(new_effective, old_effective, entry) == old_effective,
                  f"existing effective mob data changed: {row['mob_id']} Lv.{old_profile['level']}")
            for drop in new_effective["drops"].get("items", []):
                ref = drop["itemId"]["ref"]
                check(ref.startswith("item:") and len(definitions[ref.removeprefix("item:")]) == 1,
                      f"unresolved item reference: {ref}")
            loot_ref = new_effective["drops"]["lootTable"]["ref"]
            check(loot_ref.startswith("loot_table:") and
                  len(definitions[loot_ref.removeprefix("loot_table:")]) == 1,
                  f"unresolved loot reference: {loot_ref}")
            results.append({"mob_id": row["mob_id"], "level": old_profile["level"],
                            "new_item_id": row["item_id"], "new_entry_count": 1,
                            "rate_percent": 35.0, "amount": 1,
                            "existing_items": old_effective["drops"].get("items", []),
                            "exp": old_effective["drops"]["exp"],
                            "loot_table": loot_ref, "existing_effective_data_unchanged": True})

    # Gitの日本語パスquotingを解除し、所有範囲も検証する。
    changed = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "diff", "--name-only", BASE, "--"],
        cwd=ROOT, text=True, encoding="utf-8",
    ).splitlines()
    untracked = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"],
        cwd=ROOT, text=True, encoding="utf-8",
    ).splitlines()
    owned = {row[key] for row in rows for key in ("enemy_path", "material_path")}
    for path in set(changed + untracked):
        check(path in owned or path.startswith("00_docs/99_資料/field-mobs-lv50/batches/E01/"),
              f"path outside E01 ownership: {path}")

    report = {"status": "PASS", "base_commit": BASE, "yaml_files_parsed": len(files),
              "master_ids_checked": len(definitions), "introduced_duplicate_master_ids": {},
              "duplicate_ids_within_namespace": {}, "existing_cross_namespace_ids": duplicates,
              "schema_check": "変更項目の独自契約検査（Markdownスキーマは手動照合、JSON Schemaなし）",
              "profiles_checked": len(results), "effective_profiles": results,
              "scope_check": "PASS", "heavy_build": "not run"}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
