"""Read-only E02 checks against the requested base; no build or server access."""

import copy
import csv
import json
import re
import subprocess
from collections import defaultdict
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[5]
BASE = "2c419c1af8cff4ffdef63a8cf726f96a3408fe63"
BATCH = "00_docs/99_資料/field-mobs-lv50/batches/E02/"
ASSIGNMENTS = ROOT / "00_docs/99_資料/field-mobs-lv50/EXISTING_MATERIAL_ASSIGNMENTS.csv"
EXPECTED = {
    "eriva_grassboar": ("10a00302", "大草原の牙", 15, "BONE"),
    "eriva_sporecap_beast": ("10a00303", "キノコの石づき", 16, "BROWN_MUSHROOM"),
}


class UniqueKeyLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"Duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping
)


def parse(raw):
    return yaml.load(raw.decode("utf-8-sig"), Loader=UniqueKeyLoader)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def merge(base, override):
    """MobRepository: recursively merge maps, replace arrays/scalars."""
    result = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(result.get(key), dict) and isinstance(value, dict):
            result[key] = merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def profiles(mob):
    root = {key: value for key, value in mob.items() if key != "levels"}
    overrides = mob.get("levels") or [{"level": mob["level"]}]
    return [(profile["level"], merge(root, profile)) for profile in overrides]


def without_new_drop(mob, item_id):
    result = copy.deepcopy(mob)
    for container in [result, *result.get("levels", [])]:
        items = container.get("drops", {}).get("items")
        if isinstance(items, list):
            container["drops"]["items"] = [
                item for item in items
                if item.get("itemId", {}).get("ref") != f"item:{item_id}"
            ]
    return result


def main():
    with ASSIGNMENTS.open(encoding="utf-8-sig", newline="") as source:
        rows = [row for row in csv.DictReader(source) if row["batch"] == "E02"]
    require({row["mob_id"] for row in rows} == set(EXPECTED), "E02 assignment mismatch")
    allowed = {row[key] for row in rows for key in ("enemy_path", "material_path")}
    changed = set(git("diff", "--name-only", "-z", BASE).decode().split("\0"))
    untracked = set(git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    paths = (changed | untracked) - {""}
    require(all(path in allowed or path.startswith(BATCH) for path in paths),
            f"Out-of-scope paths: {sorted(paths - allowed)}")
    require(allowed <= paths, "Expected two enemies and two materials in diff")

    # Master definitions use top-level id; nested catalog entries are not masters.
    ids = defaultdict(list)
    scoped_ids = defaultdict(list)
    yaml_files = sorted((ROOT / "40_filebase").rglob("*.yml"))
    for path in yaml_files:
        text = path.read_text(encoding="utf-8-sig")
        match = re.search(r"^id:\s*([^\r\n]+)", text, re.MULTILINE)
        if match:
            item_id = str(yaml.safe_load("id: " + match.group(1))["id"])
            relative = path.relative_to(ROOT)
            ids[item_id].append(relative.as_posix())
            scoped_ids[(relative.parts[1], item_id)].append(relative.as_posix())
    duplicates = {f"{namespace}:{key}": value
                  for (namespace, key), value in scoped_ids.items() if len(value) > 1}
    require(not duplicates, f"Duplicate IDs within master namespaces: {duplicates}")
    cross_namespace_ids = {key: value for key, value in ids.items() if len(value) > 1}
    for item_id, paths_with_id in cross_namespace_ids.items():
        for path in paths_with_id:
            baseline = parse(git("show", f"{BASE}:{path}"))
            require(str(baseline["id"]) == item_id, "New cross-namespace duplicate ID")

    results = []
    for row in rows:
        mob_id = row["mob_id"]
        item_id, name, progression, icon = EXPECTED[mob_id]
        require((row["item_id"], row["name"], int(row["progression"])) ==
                (item_id, name, progression), "Reserved ID/name/progression mismatch")
        require((row["rate_percent"], row["amount"], row["luckAffected"],
                 row["hidden"], row["rarity"], row["scope"]) ==
                ("35", "1", "true", "false", "COMMON", "all_existing_level_profiles"),
                "Reserved drop contract mismatch")
        material_raw = (ROOT / row["material_path"]).read_bytes()
        material = parse(material_raw)
        required_values = {
            "schemaVersion": 1, "id": item_id, "category": "material",
            "name": f"&f{name}", "icon": icon, "rarity": "COMMON", "maxStack": 64,
            "saleValue": 0, "unTradeable": False, "unSellable": False,
        }
        require(set(material) == set(required_values) | {"lore"}, "Material schema keys")
        require(all(material.get(key) == value and type(material[key]) is type(value)
                    for key, value in required_values.items()), "Material schema values/types")
        require(isinstance(material["lore"], list) and material["lore"] and
                all(isinstance(line, str) for line in material["lore"]), "Lore string list")
        require(re.fullmatch(r"10[a-y]\d{5}", item_id) is not None, "Material ID format")
        require(ids[item_id] == [row["material_path"]], "Unique new item reference")
        text = material_raw.decode("utf-8-sig")
        require(text.startswith('# design:\n#   motif: "') and
                re.search(rf"^#   progression: {progression}$", text, re.MULTILINE),
                "Material design metadata")

        baseline_raw = git("show", f"{BASE}:{row['enemy_path']}")
        current_raw = (ROOT / row["enemy_path"]).read_bytes()
        baseline = parse(baseline_raw)
        current = parse(current_raw)
        require(current["id"] == mob_id, "Mob ID")
        require(without_new_drop(current, item_id) == baseline,
                "Non-added mob data changed")
        added = {"itemId": {"ref": f"item:{item_id}"}, "rate": 35.0,
                 "amount": 1, "luckAffected": True, "hidden": False}
        expected_bytes = (
            "    - itemId:\n"
            f"        ref: item:{item_id}\n"
            "      rate: 35.0\n"
            "      amount: 1\n"
            "      luckAffected: true\n"
            "      hidden: false\n"
        ).encode()
        require(current_raw.count(expected_bytes) == 1 and
                current_raw.replace(expected_bytes, b"", 1) == baseline_raw,
                "Enemy bytes differ beyond one appended root drop")
        require(re.search(rf"^#   progression: {progression}$",
                          baseline_raw.decode("utf-8-sig"), re.MULTILINE), "Mob progression")

        level_results = []
        old_profiles = profiles(baseline)
        new_profiles = profiles(current)
        require([level for level, _ in old_profiles] == [level for level, _ in new_profiles],
                "Level set changed")
        for (level, old), (_, new) in zip(old_profiles, new_profiles):
            items = new["drops"]["items"]
            matches = [item for item in items if item["itemId"]["ref"] == f"item:{item_id}"]
            require(matches == [added], f"Lv.{level}: exactly one new drop")
            require(items == old["drops"]["items"] + [added],
                    f"Lv.{level}: existing drops or ordering changed")
            require(without_new_drop(new, item_id) == old,
                    f"Lv.{level}: effective fields changed")
            require(all(type(matches[0][key]) is type(added[key]) for key in added),
                    "New drop value types")
            for item in items:
                ref = item["itemId"]["ref"]
                require(ref.startswith("item:") and len(ids[ref[5:]]) == 1,
                        f"Unresolved item reference: {ref}")
            loot_ref = new["drops"]["lootTable"]["ref"]
            require(loot_ref.startswith("loot_table:") and len(ids[loot_ref[11:]]) == 1,
                    f"Unresolved loot table: {loot_ref}")
            level_results.append({
                "level": level, "exp": old["drops"]["exp"],
                "existing_drops": old["drops"]["items"], "existing_loot_table": loot_ref,
                "new_drop": added, "all_existing_fields_preserved": True,
            })
        results.append({"mob_id": mob_id, "item_id": item_id, "name": name,
                        "progression": progression, "icon": icon, "levels": level_results})

    print(json.dumps({
        "status": "PASS", "base": BASE,
        "checks": ["scope", "strict_changed_yaml_parse", "material_documented_schema",
                   "all_top_level_master_id_duplicates", "item_and_loot_references",
                   "enemy_byte_preservation", "effective_full_profile_preservation",
                   "exactly_one_new_drop_per_level", "name_icon_progression"],
        "yaml_files_scanned_for_ids": len(yaml_files), "master_ids": len(ids),
        "duplicate_ids_within_namespace": duplicates,
        "preexisting_cross_namespace_reused_ids": cross_namespace_ids,
        "parsed_changed_yaml": 4,
        "effective_level_count": sum(len(result["levels"]) for result in results),
        "materials": results,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
