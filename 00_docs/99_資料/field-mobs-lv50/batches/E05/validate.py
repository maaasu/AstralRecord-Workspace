"""E05の所有範囲、素材、参照、既存全レベルのドロップ保持を検証する。"""

import copy
import csv
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

import yaml


BASE = "3444b74b24ed99f11fbb93d5e26bfe00ad7f2780"
ASSIGNMENTS_COMMIT = "2c419c1af8cff4ffdef63a8cf726f96a3408fe63"
ROOT = Path(__file__).resolve().parents[5]
BATCH = Path(__file__).resolve().parent
BATCH_PATH = "00_docs/99_資料/field-mobs-lv50/batches/E05/"
ICONS = {"10a00308": "CLAY_BALL", "10a00309": "DRIED_KELP"}
EXPECTED_LEVELS = {"midgard_clay_guard": [3], "midgard_forest_spider": [7, 8]}


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


def git(*args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT)


def merge(base, override):
    """MobRepository同様、objectは再帰mergeし、配列は置換する。"""
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
    return ([(profile["level"], merge(root, profile)) for profile in profiles]
            if profiles else [(root["level"], root)])


def remove_added_drop(document, item_id):
    items = document["drops"]["items"]
    expected = {"itemId": {"ref": f"item:{item_id}"}, "rate": 35.0,
                "amount": 1, "luckAffected": True, "hidden": False}
    selected = [item for item in items if item.get("itemId") == expected["itemId"]]
    assert selected == [expected], f"expected exactly one configured drop: {item_id}"
    assert items[-1] == expected, f"new drop must be appended: {item_id}"
    items.pop()


def without_added_drops(document, item_id):
    result = copy.deepcopy(document)
    remove_added_drop(result, item_id)
    for profile in result.get("levels", []):
        if "items" in profile.get("drops", {}):
            remove_added_drop(profile, item_id)
    return result


def master_ids(documents):
    result = {}
    for path, document in documents.items():
        if isinstance(document, dict) and isinstance(document.get("id"), str):
            result.setdefault(document["id"], []).append(path)
    return {key: sorted(paths) for key, paths in result.items()}


def duplicates(ids):
    return {key: paths for key, paths in ids.items() if len(paths) > 1}


def main():
    assignment_path = "00_docs/99_資料/field-mobs-lv50/EXISTING_MATERIAL_ASSIGNMENTS.csv"
    assignment_csv = git("show", f"{ASSIGNMENTS_COMMIT}:{assignment_path}").decode("utf-8-sig")
    rows = [row for row in csv.DictReader(io.StringIO(assignment_csv)) if row["batch"] == "E05"]
    assert [(row["mob_id"], row["item_id"]) for row in rows] == [
        ("midgard_clay_guard", "10a00308"), ("midgard_forest_spider", "10a00309")]
    assert all((row["rate_percent"], row["amount"], row["rarity"], row["luckAffected"], row["hidden"])
               == ("35", "1", "COMMON", "true", "false") for row in rows)

    changed = set(git("diff", "--name-only", BASE).decode("utf-8").splitlines())
    changed.update(git("ls-files", "--others", "--exclude-standard").decode("utf-8").splitlines())
    owned = {row[key] for row in rows for key in ("enemy_path", "material_path")}
    assert all(path in owned or path.startswith(BATCH_PATH) for path in changed), changed - owned

    original, original_text = {}, {}
    with tarfile.open(fileobj=io.BytesIO(git("archive", BASE, "40_filebase"))) as archive:
        for member in archive.getmembers():
            if member.isfile() and member.name.endswith(".yml"):
                raw = archive.extractfile(member).read().decode("utf-8-sig")
                original_text[member.name] = raw
                original[member.name] = yaml.safe_load(raw)
    files = sorted((ROOT / "40_filebase").rglob("*.yml"))
    current = {path.relative_to(ROOT).as_posix(): yaml.safe_load(path.read_text(encoding="utf-8-sig"))
               for path in files}
    old_ids, new_ids = master_ids(original), master_ids(current)
    assert duplicates(old_ids) == duplicates(new_ids), "introduced duplicate master ID"

    result = {"batch": "E05", "base": BASE, "assignments_commit": ASSIGNMENTS_COMMIT,
              "status": "PASS", "ownership_scope": "passed", "yaml_files_parsed": len(files),
              "master_ids": len(new_ids), "existing_duplicate_ids": duplicates(new_ids),
              "introduced_duplicate_ids": [], "mobs": []}
    for row in rows:
        enemy_path, material_path, item_id = row["enemy_path"], row["material_path"], row["item_id"]
        enemy = yaml.load((ROOT / enemy_path).read_text(encoding="utf-8-sig"), Loader=UniqueKeysLoader)
        old = original[enemy_path]
        assert enemy["id"] == row["mob_id"]
        assert item_id not in old_ids and new_ids[item_id] == [material_path]
        assert without_added_drops(enemy, item_id) == old, "existing root or profile data changed"
        old_profiles, new_profiles = effective_profiles(old), effective_profiles(enemy)
        assert [level for level, _ in old_profiles] == EXPECTED_LEVELS[row["mob_id"]]
        assert [level for level, _ in new_profiles] == EXPECTED_LEVELS[row["mob_id"]]
        for (level, before), (_, after) in zip(old_profiles, new_profiles):
            restored = copy.deepcopy(after)
            remove_added_drop(restored, item_id)
            assert restored == before, f"existing effective data changed at Lv.{level}"
            for drop in after["drops"]["items"]:
                ref = drop["itemId"]["ref"]
                assert ref.startswith("item:") and len(new_ids.get(ref[5:], [])) == 1
                assert new_ids[ref[5:]][0].startswith("40_filebase/10.features.item/")
            loot = after["drops"].get("lootTable", {}).get("ref")
            if loot:
                assert loot.startswith("loot_table:")
                paths = new_ids.get(loot.split(":", 1)[1], [])
                assert len(paths) == 1 and paths[0].startswith("40_filebase/80.shared.loot/table/")

        text = (ROOT / material_path).read_text(encoding="utf-8-sig")
        material = yaml.load(text, Loader=UniqueKeysLoader)
        assert set(material) == {"schemaVersion", "id", "category", "name", "icon", "rarity",
                                 "maxStack", "saleValue", "lore", "unTradeable", "unSellable"}
        assert type(material["schemaVersion"]) is int and material["schemaVersion"] == 1
        assert material["id"] == item_id and re.fullmatch(r"10[a-y][0-9]{5}", item_id)
        assert re.fullmatch(rf"v1\.{item_id}\.[a-z0-9_]+\.yml", Path(material_path).name)
        assert material["category"] == "material" and material["name"] == "&f" + row["name"]
        assert material["icon"] == ICONS[item_id] and material["rarity"] == "COMMON"
        assert type(material["maxStack"]) is int and material["maxStack"] == 64
        assert type(material["saleValue"]) is int and material["saleValue"] == 0
        assert material["unTradeable"] is False and material["unSellable"] is False
        assert isinstance(material["lore"], list) and material["lore"]
        assert all(isinstance(line, str) and re.search(r"[ぁ-んァ-ヶ一-龯]", line) for line in material["lore"])
        assert f'#   progression: {row["progression"]}\n' in text
        assert f'#   progression: {row["progression"]}\n' in original_text[enemy_path]
        assert re.search(r'^# design:\n#   motif: "[^"\n]+"\n', text)
        result["mobs"].append({
            "mob_id": row["mob_id"], "item_id": item_id, "enemy_path": enemy_path,
            "material_path": material_path, "progression": int(row["progression"]),
            "effective_levels": [level for level, _ in new_profiles],
            "explicit_item_overrides": [profile["level"] for profile in enemy.get("levels", [])
                                        if "items" in profile.get("drops", {})],
            "new_drop_occurrences_per_effective_level": 1,
            "existing_root_and_effective_data_unchanged": True,
            "item_and_loot_references_resolved": True, "rate_percent": 35.0, "amount": 1,
            "luckAffected": True, "hidden": False, "icon": material["icon"],
            "baseline_icon_owners": sorted(path for path, d in original.items()
                                           if isinstance(d, dict) and d.get("icon") == material["icon"]),
        })
    report = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    (BATCH / "validation.json").write_text(report, encoding="utf-8", newline="\n")
    print(report)


if __name__ == "__main__":
    main()
