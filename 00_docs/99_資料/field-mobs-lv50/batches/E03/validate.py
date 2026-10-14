"""E03のYAML、予約ID、参照、全実効profileを基点と比較する。build不要。"""

import copy
import csv
import json
from pathlib import Path
import re
import subprocess

import yaml


BASE = "2c419c1af8cff4ffdef63a8cf726f96a3408fe63"
ROOT = Path(__file__).resolve().parents[5]
BATCH = Path(__file__).resolve().parent


class UniqueKeysLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        assert key not in result, f"duplicate YAML key: {key}"
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeysLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping
)


def parse(text):
    return yaml.load(text, Loader=UniqueKeysLoader)


def baseline(path):
    return subprocess.check_output(
        ["git", "show", f"{BASE}:{path}"], cwd=ROOT
    ).decode("utf-8-sig")


def merge(base, override):
    result = copy.deepcopy(base)
    for key, value in override.items():
        result[key] = (
            merge(result[key], value)
            if isinstance(result.get(key), dict) and isinstance(value, dict)
            else copy.deepcopy(value)
        )
    return result


def effective_profiles(document):
    root = {key: value for key, value in document.items() if key != "levels"}
    profiles = document.get("levels") or []
    if not profiles:
        return [(root["level"], root)]
    return [(profile["level"], merge(root, profile)) for profile in profiles]


def without_new_drop(document, item_id):
    result = copy.deepcopy(document)
    items = result.get("drops", {}).get("items", [])
    selected = [item for item in items if item.get("itemId") == {"ref": f"item:{item_id}"}]
    expected = {
        "itemId": {"ref": f"item:{item_id}"},
        "rate": 35.0,
        "amount": 1,
        "luckAffected": True,
        "hidden": False,
    }
    assert selected == [expected], f"expected exactly one new drop: {item_id}"
    assert items[-1] == expected, f"new drop must be appended: {item_id}"
    items.remove(selected[0])
    return result


def main():
    assignments = ROOT / "00_docs/99_資料/field-mobs-lv50/EXISTING_MATERIAL_ASSIGNMENTS.csv"
    with assignments.open(encoding="utf-8-sig", newline="") as stream:
        rows = [row for row in csv.DictReader(stream) if row["batch"] == "E03"]
    assert [(row["mob_id"], row["item_id"]) for row in rows] == [
        ("eriva_starhorn_guardian", "10a00304"),
        ("eriva_thornfang_wolf", "10a00305"),
    ]
    assert all((row["rate_percent"], row["amount"], row["rarity"], row["luckAffected"], row["hidden"]) ==
               ("35", "1", "COMMON", "true", "false") for row in rows)

    changed = set(subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "diff", "--name-only", BASE], cwd=ROOT
    ).decode("utf-8").splitlines())
    changed.update(subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"], cwd=ROOT
    ).decode("utf-8").splitlines())
    owned = {row[key] for row in rows for key in ("enemy_path", "material_path")}
    # Git quotes non-ASCII paths unless core.quotepath is disabled.
    assert all(path in owned or path.startswith("00_docs/99_資料/field-mobs-lv50/batches/E03/")
               for path in changed), f"out-of-scope changes: {changed - owned}"

    files = sorted((ROOT / "40_filebase").rglob("*.yml"))
    current = {path.relative_to(ROOT).as_posix(): yaml.safe_load(path.read_text(encoding="utf-8-sig"))
               for path in files}
    original = dict(current)
    for row in rows:
        original.pop(row["material_path"], None)
        original[row["enemy_path"]] = parse(baseline(row["enemy_path"]))

    def ids(documents):
        result = {}
        for path, document in documents.items():
            if isinstance(document, dict) and isinstance(document.get("id"), str):
                result.setdefault(document["id"], []).append(path)
        return result

    old_ids, new_ids = ids(original), ids(current)
    old_duplicates = {key: paths for key, paths in old_ids.items() if len(paths) > 1}
    new_duplicates = {key: paths for key, paths in new_ids.items() if len(paths) > 1}
    assert old_duplicates == new_duplicates, "introduced a duplicate master ID"

    result = {"batch": "E03", "base": BASE, "ownership_scope": "passed", "yaml_files_parsed": len(files),
              "master_ids": len(new_ids), "existing_duplicate_ids": new_duplicates,
              "introduced_duplicate_ids": [], "mobs": []}
    for row in rows:
        enemy_path, material_path = row["enemy_path"], row["material_path"]
        enemy = parse((ROOT / enemy_path).read_text(encoding="utf-8-sig"))
        old = original[enemy_path]
        item_id = row["item_id"]
        assert item_id not in old_ids
        assert new_ids[item_id] == [material_path]
        assert without_new_drop(enemy, item_id) == old, "existing root data changed"
        old_profiles, new_profiles = effective_profiles(old), effective_profiles(enemy)
        assert [level for level, _ in old_profiles] == [level for level, _ in new_profiles]
        for (level, before), (_, after) in zip(old_profiles, new_profiles):
            assert without_new_drop(after, item_id) == before, f"existing effective data changed at Lv.{level}"

        material_text = (ROOT / material_path).read_text(encoding="utf-8-sig")
        material = parse(material_text)
        required = {"schemaVersion", "id", "category", "name", "icon", "rarity",
                    "maxStack", "saleValue", "lore", "unTradeable", "unSellable"}
        assert set(material) == required
        assert material["id"] == item_id and re.fullmatch(r"10[a-y][0-9]{5}", item_id)
        assert material["schemaVersion"] == 1 and material["category"] == "material"
        assert material["name"] == "&f" + row["name"]
        assert material["rarity"] == "COMMON"
        assert type(material["maxStack"]) is int and material["maxStack"] == 64
        assert type(material["saleValue"]) is int and material["saleValue"] == 0
        assert material["unTradeable"] is False and material["unSellable"] is False
        assert isinstance(material["lore"], list) and all(isinstance(line, str) for line in material["lore"])
        assert f'#   progression: {row["progression"]}\n' in material_text
        assert f'#   progression: {row["progression"]}\n' in baseline(enemy_path)
        assert re.search(r'^#   motif: "[^"\n]+"$', material_text, re.MULTILINE)
        assert material["icon"] == {"10a00304": "GOAT_HORN", "10a00305": "POINTED_DRIPSTONE"}[item_id]

        def icon_owners(documents, icon):
            return sorted(path for path, document in documents.items()
                          if isinstance(document, dict) and document.get("icon") == icon)

        # All direct drop and loot references in the changed mobs remain resolvable.
        for _, profile in new_profiles:
            for drop in profile["drops"]["items"]:
                ref = drop["itemId"]["ref"]
                assert ref.startswith("item:") and len(new_ids.get(ref[5:], [])) == 1
            loot_ref = profile["drops"].get("lootTable", {}).get("ref")
            if loot_ref:
                assert loot_ref.startswith("loot_table:")
                assert len(new_ids.get(loot_ref.split(":", 1)[1], [])) == 1

        result["mobs"].append({
            "mob_id": row["mob_id"], "enemy_path": enemy_path, "material_path": material_path,
            "item_id": item_id, "effective_levels": [level for level, _ in new_profiles],
            "explicit_item_overrides": [p["level"] for p in enemy.get("levels", []) if "items" in p.get("drops", {})],
            "new_drop_occurrences_per_effective_level": 1,
            "existing_root_and_effective_data_unchanged": True,
            "item_and_loot_references_resolved": True, "progression": int(row["progression"]),
            "icon": material["icon"], "baseline_icon_owners": icon_owners(original, material["icon"]),
            "rate_percent": 35.0, "amount": 1, "luckAffected": True, "hidden": False,
        })
    report = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    (BATCH / "validation.json").write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
