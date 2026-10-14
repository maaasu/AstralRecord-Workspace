"""Lv25ルミナ専用の静的検証。PyYAML使用。重いbuild/DB/実サーバーは起動しない。"""
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path
import csv
import json
import math
import re
import subprocess

import yaml


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = "0a30bedc1d8bf7eec4c2ca5f654dd621985e1f1a"
GEAR = ROOT / "40_filebase/10.features.item/20.equipment"
EXPECTED = {f"20a{i:05}" for i in range(419, 438)}
SETS = {"lumina_guard", "lumina_hunt", "lumina_magia"}


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def load(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)


def decimal(value):
    assert isinstance(value, (int, float, str)) and not isinstance(value, bool)
    number = Decimal(str(value))
    assert number.is_finite() and number.as_tuple().exponent >= -4
    return number


def check_random(stat):
    assert set(stat) <= {"status", "type", "value", "random"}
    base = decimal(stat["value"])
    rnd = stat.get("random")
    if rnd is None:
        return {"min": str(base), "max": str(base), "mean": str(base), "zeroChance": "0"}
    assert set(rnd) <= {"offsetMin", "offsetMax", "step", "zeroChance"}
    assert all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in rnd.values())
    low, high, step = [decimal(rnd[k]) for k in ("offsetMin", "offsetMax", "step")]
    zero = decimal(rnd.get("zeroChance", 0))
    assert low <= high and step > 0 and (high-low) % step == 0 and 0 <= zero <= 1
    count = int((high-low)/step) + 1
    assert 1 <= count <= 1_000_000
    if zero:
        assert not base+low <= 0 <= base+high
    for value in (base, low, high, step, base+low, base+high):
        assert Decimal(str(float(value))) == value
    if count > 1:
        assert float(step) >= math.ulp(max(abs(float(base+low)), abs(float(base+high))))
    return {"min": str(min(Decimal(0), base+low) if zero else base+low),
            "max": str(base+high), "mean": str((1-zero)*(base+(low+high)/2)),
            "zeroChance": str(zero), "regularMin": str(base+low),
            "regularMax": str(base+high), "step": str(step), "candidates": count}


def run():
    masters, by_id = {}, defaultdict(list)
    for path in sorted((ROOT/"40_filebase").rglob("*.yml")):
        data = load(path)
        masters[path] = data
        if isinstance(data, dict) and "id" in data:
            by_id[data["id"]].append(path.relative_to(ROOT).as_posix())
    duplicates = {key: value for key, value in by_id.items() if len(value) > 1}
    # 既存skill/buffとworld/fishingの名前空間間重複を基点と照合する。
    # 既存の同名をこの作業で直さず、新規の衝突だけをブロックする。
    introduced_duplicates = {}
    for key, paths in duplicates.items():
        for name in paths:
            original = subprocess.run(["git", "show", f"{BASE}:{name}"], cwd=ROOT,
                                      capture_output=True, text=True, encoding="utf-8")
            if original.returncode or yaml.safe_load(original.stdout).get("id") != key:
                introduced_duplicates[key] = paths
    assert not introduced_duplicates, introduced_duplicates
    items = {d["id"]: (p, d) for p, d in masters.items()
             if p.parent == GEAR and isinstance(d, dict) and d.get("id") in EXPECTED}
    assert set(items) == EXPECTED
    status_ids = {s["id"] for s in load(ROOT/"40_filebase/75.shared.status/v1.status_types.yml")["statuses"]}
    tag_ids = {t["id"] for t in load(ROOT/"40_filebase/76.shared.tag/v1.tags.yml")["tags"]}
    manifest = json.loads((HERE/"material_manifest.json").read_text(encoding="utf-8"))
    assert manifest["baseCommit"] == BASE and manifest["status"] == "proposal_only"
    entries = {e["itemId"]: e for e in manifest["products"]}
    assert set(entries) == EXPECTED and len(manifest["products"]) == 19
    source_rows = list(csv.DictReader((ROOT/"00_docs/99_資料/field-mobs-lv50/integration/FINAL_NEW_MATERIALS.csv").open(encoding="utf-8-sig", newline="")))
    new_materials = {r["item_id"]: r for r in source_rows}
    source_catalog = {s["itemId"]: s for s in manifest["materialSources"]}
    assert len(source_catalog) == len(manifest["materialSources"]) == 11
    for source in source_catalog.values():
        actual = masters[ROOT/source["sourcePath"]]
        assert actual["id"] == source["sourceId"]
        drops = actual["drops"]["items"]
        def drop_id(row):
            value = row["itemId"]
            return (value["ref"] if isinstance(value, dict) else value).removeprefix("item:")
        drop, = [row for row in drops if drop_id(row) == source["itemId"]]
        assert float(drop["rate"]) == source["dropRatePercent"] and str(drop["amount"]) == "1"
        if source["kind"] == "gathering":
            assert actual["requiredToolTags"] == source["requiredToolTags"]
            assert actual["level"] == source["gatheringLevel"] == source["sourceMinLevel"]
            assert actual["maxHealth"] == source["maxHealth"]
            assert abs(source["secondsAtSpeed4"]-math.ceil(actual["maxHealth"]/4)*.4) < 1e-8
        else:
            assert min(row["level"] for row in actual["levels"]) == source["sourceMinLevel"]
    slots = defaultdict(list)
    set_members = defaultdict(list)
    ranges = {}
    for item_id, (path, data) in sorted(items.items()):
        text = path.read_text(encoding="utf-8")
        assert text.startswith('# design:\n#   motif:') and '#   progression: 25\n' in text
        assert data["schemaVersion"] == 1 and data["category"] == "equipment"
        assert data["name"].startswith("&bルミナ") and data["maxStack"] == 1
        assert data["rarity"] == "RARE" and "customModelData" not in data
        assert path.name.startswith(f"v1.{item_id}.")
        eq = data["equipment"]
        assert set(eq) <= {"slot", "handType", "tag", "requiredLevel", "stats", "setId", "durability", "enchant", "rune", "enhance", "transcendence"}
        assert eq["requiredLevel"] == 25 and eq["enchant"] == {"maxSlots": 3}
        assert len({(s["status"], s["type"]) for s in eq["stats"]}) == len(eq["stats"])
        assert all(s["status"] in status_ids and s["type"] == "FLAT" for s in eq["stats"])
        ranges[item_id] = {s["status"]: check_random(s) for s in eq["stats"]}
        if eq["slot"] == "WEAPON":
            main = {"SWORD": "MELEE_ATTACK", "HAMMER": "MELEE_ATTACK", "SPEAR": "MELEE_ATTACK", "SHORTBOW": "RANGED_ATTACK", "LONGBOW": "RANGED_ATTACK", "WAND": "MAGIC_ATTACK", "STAFF": "MAGIC_ATTACK"}[eq["tag"]]
            assert eq["tag"] in tag_ids and eq["handType"] == "ONE"
            assert eq["rune"] == {"maxSlots": 1} and main in ranges[item_id]
            attrs = [s for s in eq["stats"] if s["status"].endswith("_DAMAGE_INCREASE") and s["status"] != "NORMAL_ATTACK_DAMAGE_INCREASE"]
            assert len(attrs) == 2
            for s in attrs:
                assert s["status"] in {"FIRE_DAMAGE_INCREASE", "ICE_DAMAGE_INCREASE", "LIGHTNING_DAMAGE_INCREASE"}
                assert decimal(s["value"]) == 0
                assert s["random"] == {"offsetMin": 0.1, "offsetMax": 7, "step": 0.1, "zeroChance": 0.96}
        else:
            main = "DEFENSE"
            assert eq["slot"] in {"HEAD", "CHEST", "LEGS", "FEET"} and "rune" not in eq
            assert len([s for s in eq["stats"] if "random" in s]) == 1
            assert data["icon"].startswith("LEATHER_") and re.fullmatch(r"#[0-9A-F]{6}", data["appearance"]["color"])
            assert eq["setId"] in SETS
            set_members[eq["setId"]].append(eq["slot"])
        assert eq["durability"]["max"] > 0 and eq["durability"]["consume"] == 1
        assert eq["enhance"]["maxLevel"] == 15
        levels = eq["enhance"]["levels"]
        assert [row["level"] for row in levels] == list(range(1, 31))
        for row in levels:
            n = row["level"]
            expected = 1 if n == 1 else 0.75 if n <= 4 else 0.5 if n <= 6 else 0.35 if n <= 8 else 0.2 if n == 9 else 0.05
            assert row["successRate"] == expected and row["failAction"] == "NONE"
            assert main in {s["status"] for s in row["statIncrease"]}
            for stat in row["statIncrease"]:
                assert stat["status"] in status_ids and stat["type"] == "FLAT" and "random" not in stat
                decimal(stat["value"])
        trans, = eq["transcendence"]
        assert trans["rank"] == 1 and trans["requiredEnhanceLevel"] == 15
        assert trans["overrides"]["enhance"] == {"maxLevel": 30}
        assert set(trans["overrides"]) == {"name", "enhance"}
        assert trans["requiredCurrency"] > 0
        for mat in trans["requiredMaterials"]:
            assert mat["itemId"] in by_id and mat["amount"] > 0
        entry = entries[item_id]
        assert entry["outputAmount"] == 1 and entry["priceGold"] > data["saleValue"]
        assert entry["page"] == 2 and 0 <= entry["slot"] <= 27
        slots[entry["shopId"]].append(entry["slot"])
        assert entry["shopId"] in by_id
        assert len(entry["requiredItems"]) == 3
        assert sum(math.ceil(m["amount"]/64) for m in entry["requiredItems"]) + 1 <= 4
        for mat in entry["requiredItems"] + trans["requiredMaterials"]:
            assert mat["itemId"] in by_id and 0 < mat["amount"] <= 64
            source = source_catalog[mat["itemId"]]
            assert source["sourceMinLevel"] <= 25
            if mat["itemId"] in new_materials:
                record = new_materials[mat["itemId"]]
                assert record["rarity"] == "normal" and float(record["rate_final"]) >= 35
                assert min(map(int, record["levels"].split(','))) <= 25
            else:
                assert source["kind"] == "gathering"
        if eq["slot"] == "CHEST":
            assert sum(m["amount"] for m in entry["requiredItems"] if m["itemId"] in {"10a00086", "10a00087", "10a00089"}) == 46
    assert all(len(value) == len(set(value)) for value in slots.values())
    assert set(set_members) == SETS
    for set_id, members in set_members.items():
        assert Counter(members) == Counter({"HEAD": 1, "CHEST": 1, "LEGS": 1, "FEET": 1})
        path, = [ROOT/p for p in by_id[set_id]]
        data = masters[path]
        assert data["schemaVersion"] == 1 and '#   progression: 25\n' in path.read_text(encoding="utf-8")
        main = {"lumina_guard": "MELEE_ATTACK", "lumina_hunt": "RANGED_ATTACK", "lumina_magia": "MAGIC_ATTACK"}[set_id]
        assert data["pieces"] == [{"count": 2, "stats": [{"status": main, "type": "FLAT", "value": 8}]}, {"count": 4, "stats": [{"status": "ACCURACY", "type": "FLAT", "value": 2}]}]
    csv_entries = list(csv.DictReader((HERE/"material_manifest.csv").open(encoding="utf-8-sig", newline="")))
    assert len(csv_entries) == 19
    for row in csv_entries:
        entry = entries[row["item_id"]]
        assert int(row["gold"]) == entry["priceGold"] and int(row["slot"]) == entry["slot"] and int(row["page"]) == entry["page"]
        for i, material in enumerate(entry["requiredItems"], 1):
            assert row[f"material_{i}"] == material["itemId"] and int(row[f"amount_{i}"]) == material["amount"]
        assert int(row["evolution_gold"]) == entry["evolution"]["requiredCurrency"]
        for i, material in enumerate(entry["evolution"]["requiredMaterials"], 1):
            assert row[f"evolution_material_{i}"] == material["itemId"] and int(row[f"evolution_amount_{i}"]) == material["amount"]
    changed = subprocess.check_output(["git", "-c", "core.quotepath=false", "diff", "--name-only", BASE], cwd=ROOT, text=True, encoding="utf-8").splitlines()
    untracked = subprocess.check_output(["git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True, encoding="utf-8").splitlines()
    allowed = {path.relative_to(ROOT).as_posix() for path, data in items.values()}
    allowed.update(f"40_filebase/10.features.item/20.equipment/set_effect/v1.{s}.yml" for s in SETS)
    for name in changed + untracked:
        assert name.startswith("00_docs/99_資料/eriva-lv25-lumina/") or name in allowed, name
    result = {"status": "PASS", "baseCommit": BASE, "yamlFilesParsed": len(masters),
              "introducedRootIdDuplicates": introduced_duplicates, "unchangedBaselineCrossNamespaceDuplicates": duplicates,
              "equipmentCount": len(items), "setCount": len(SETS), "proposalProducts": len(entries),
              "weaponAttributeProbability": {"bothZero": "0.9216", "exactlyOneNonzero": "0.0768", "bothNonzero": "0.0016", "anyNonzero": "0.0784"},
              "ranges": ranges,
              "limitations": ["Markdown schema contract subset checked statically; application importer not executed", "Runtime crafting, persistence, appearance, 100-battle combat and source placement not verified", "Shared shop/recipe application deferred to integration"]}
    (HERE/"validation_result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")
    print(json.dumps({k: v for k, v in result.items() if k != "ranges"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
