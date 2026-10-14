"""アストラ専用の静的受入検査。実アプリ・DB・サーバーは起動しない。"""
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
EXPECTED = {f"20a{i:05}" for i in range(438, 457)}
SETS = {"astra_guard": "MELEE_ATTACK", "astra_hunt": "RANGED_ATTACK", "astra_magia": "MAGIC_ATTACK"}
TAG_MAIN = {"SWORD": "MELEE_ATTACK", "HAMMER": "MELEE_ATTACK", "SPEAR": "MELEE_ATTACK", "SHORTBOW": "RANGED_ATTACK", "LONGBOW": "RANGED_ATTACK", "WAND": "MAGIC_ATTACK", "STAFF": "MAGIC_ATTACK"}
ATTRIBUTES = {f"{element}_DAMAGE_INCREASE" for element in ("FIRE", "ICE", "LIGHTNING")}


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        assert key not in result, f"duplicate YAML key: {key}"
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def load(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)


def number(value):
    assert isinstance(value, (int, float, str)) and not isinstance(value, bool), value
    value = Decimal(str(value))
    assert value.is_finite() and value.as_tuple().exponent >= -4, value
    return value


def random_range(stat):
    assert set(stat) <= {"status", "type", "value", "random"}
    base = number(stat["value"])
    rnd = stat.get("random")
    if rnd is None:
        return {"min": str(base), "max": str(base), "mean": str(base)}
    assert {"offsetMin", "offsetMax", "step"} <= set(rnd) <= {"offsetMin", "offsetMax", "step", "zeroChance"}
    assert all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in rnd.values())
    low, high, step = (number(rnd[k]) for k in ("offsetMin", "offsetMax", "step"))
    zero = number(rnd.get("zeroChance", 0))
    assert low <= high and step > 0 and (high-low) % step == 0 and 0 <= zero <= 1
    count = int((high-low)/step)+1
    assert 1 <= count <= 1_000_000
    if zero:
        assert not base+low <= 0 <= base+high
    for value in (base, low, high, step, base+low, base+high):
        assert Decimal(str(float(value))) == value
    if count > 1:
        assert float(step) >= math.ulp(max(abs(float(base+low)), abs(float(base+high))))
    return {"min": str(min(Decimal(0), base+low) if zero else base+low), "max": str(base+high),
            "mean": str((1-zero)*(base+(low+high)/2)), "zeroChance": str(zero),
            "regularMin": str(base+low), "regularMax": str(base+high), "step": str(step), "candidates": count}


def git(*args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", *args], cwd=ROOT, encoding="utf-8").splitlines()


def run():
    masters, ids = {}, defaultdict(list)
    for path in sorted((ROOT/"40_filebase").rglob("*.yml")):
        data = load(path)
        masters[path] = data
        if isinstance(data, dict) and "id" in data:
            ids[data["id"]].append(path.relative_to(ROOT).as_posix())
    duplicates = {key: paths for key, paths in ids.items() if len(paths) > 1}
    for key, paths in duplicates.items():
        for path in paths:
            original = "\n".join(git("show", f"{BASE}:{path}"))
            assert yaml.safe_load(original).get("id") == key, (key, paths)
    items = {data["id"]: (path, data) for path, data in masters.items()
             if path.parent == GEAR and isinstance(data, dict) and data.get("id") in EXPECTED}
    assert set(items) == EXPECTED and all(len(ids[key]) == 1 for key in EXPECTED | set(SETS))
    statuses = {row["id"] for row in load(ROOT/"40_filebase/75.shared.status/v1.status_types.yml")["statuses"]}
    tags = {row["id"] for row in load(ROOT/"40_filebase/76.shared.tag/v1.tags.yml")["tags"]}
    manifest = json.loads((HERE/"material_manifest.json").read_text(encoding="utf-8"))
    assert manifest["baseCommit"] == BASE and manifest["status"] == "proposal_only"
    products = {row["itemId"]: row for row in manifest["products"]}
    assert set(products) == EXPECTED and len(manifest["products"]) == 19
    sources = {row["itemId"]: row for row in manifest["materialSources"]}
    assert len(sources) == len(manifest["materialSources"])
    with (ROOT/"00_docs/99_資料/field-mobs-lv50/integration/FINAL_NEW_MATERIALS.csv").open(encoding="utf-8-sig", newline="") as f:
        new_materials = {r["item_id"]: r for r in csv.DictReader(f)}
    assert len(new_materials) == 33
    for source in sources.values():
        actual = masters[ROOT/source["sourcePath"]]
        assert actual["id"] == source["sourceId"]
        def drop_id(row):
            value = row["itemId"]
            return (value["ref"] if isinstance(value, dict) else value).removeprefix("item:")
        drop, = [r for r in actual["drops"]["items"] if drop_id(r) == source["itemId"]]
        assert float(drop["rate"]) == source["dropRatePercent"] and str(drop["amount"]) == source["amount"]
        if source["kind"] == "gathering":
            assert actual["requiredToolTags"] == source["requiredToolTags"]
            assert actual["level"] == source["gatheringLevel"] == source["sourceMinLevel"]
            assert actual["maxHealth"] == source["maxHealth"]
            assert math.isclose(source["secondsAtSpeed4"], math.ceil(actual["maxHealth"]/4)*.4)
            assert source["dropRatePercent"] == 100, "rare gathering must not be mandatory"
        else:
            assert min(row["level"] for row in actual["levels"]) == source["sourceMinLevel"] <= 30
            confirmed = new_materials[source["itemId"]]
            assert confirmed["rarity"] == "normal" and float(confirmed["rate_final"]) == source["dropRatePercent"]
            assert min(map(int, confirmed["levels"].split(','))) <= 30
    set_members, slots, ranges, inventory_stacks = defaultdict(list), defaultdict(list), {}, {}
    for item_id, (path, data) in sorted(items.items()):
        content = path.read_text(encoding="utf-8")
        assert content.startswith('# design:\n#   motif:') and '#   progression: 29\n' in content
        assert data["schemaVersion"] == 1 and data["category"] == "equipment"
        assert data["name"].startswith("&bアストラ") and data["maxStack"] == 1 and data["rarity"] == "RARE"
        assert "customModelData" not in data and path.name.startswith(f"v1.{item_id}.")
        eq = data["equipment"]
        assert set(eq) <= {"slot", "handType", "tag", "requiredLevel", "stats", "setId", "durability", "enchant", "rune", "enhance", "transcendence"}
        assert eq["requiredLevel"] == 30 and eq["enchant"] == {"maxSlots": 4}
        assert len({(s["status"], s["type"]) for s in eq["stats"]}) == len(eq["stats"])
        assert all(s["status"] in statuses and s["type"] == "FLAT" for s in eq["stats"])
        ranges[item_id] = {s["status"]: random_range(s) for s in eq["stats"]}
        if eq["slot"] == "WEAPON":
            main = TAG_MAIN[eq["tag"]]
            assert eq["tag"] in tags and eq["handType"] == "ONE" and eq["rune"] == {"maxSlots": 1}
            assert main in ranges[item_id] and data["iconGlint"] is True
            attrs = [s for s in eq["stats"] if s["status"] in ATTRIBUTES]
            assert {s["status"] for s in attrs} == ATTRIBUTES
            for s in attrs:
                assert s["value"] == 0 and s["random"] == {"offsetMin": .1, "offsetMax": 10, "step": .1, "zeroChance": .95}
        else:
            main = "DEFENSE"
            assert eq["slot"] in {"HEAD", "CHEST", "LEGS", "FEET"} and "rune" not in eq
            assert [s["status"] for s in eq["stats"] if "random" in s] == ["DEFENSE"]
            rnd = next(s["random"] for s in eq["stats"] if s["status"] == "DEFENSE")
            assert max(abs(rnd["offsetMin"]), abs(rnd["offsetMax"])) <= 6
            assert data["icon"].startswith("LEATHER_") and re.fullmatch(r"#[0-9A-F]{6}", data["appearance"]["color"])
            assert eq["setId"] in SETS
            set_members[eq["setId"]].append(eq["slot"])
        assert eq["durability"]["max"] > 0 and eq["durability"]["consume"] == 1
        assert eq["enhance"]["maxLevel"] == 15
        levels = eq["enhance"]["levels"]
        assert [r["level"] for r in levels] == list(range(1, 31))
        for r in levels:
            n = r["level"]
            rate = 1 if n == 1 else .75 if n <= 4 else .5 if n <= 6 else .35 if n <= 8 else .2 if n == 9 else .05
            assert r["successRate"] == rate and r["failAction"] == "NONE"
            assert main in {s["status"] for s in r["statIncrease"]}
            for stat in r["statIncrease"]:
                assert stat["status"] in statuses and stat["type"] == "FLAT" and "random" not in stat
                assert number(stat["value"]) >= 0
        trans, = eq["transcendence"]
        assert trans["rank"] == 1 and trans["requiredEnhanceLevel"] == 15
        assert trans["overrides"]["enhance"] == {"maxLevel": 30} and set(trans["overrides"]) == {"name", "enhance"}
        entry = products[item_id]
        assert entry["evolution"] == {key: trans[key] for key in ("requiredMaterials", "requiredCurrency")}
        assert trans["requiredCurrency"] > 0 and entry["priceGold"] > data["saleValue"]
        assert entry["outputAmount"] == 1 and entry["page"] == 2 and 0 <= entry["slot"] <= 27
        assert entry["shopId"] in ids
        slots[entry["shopId"]].append(entry["slot"])
        assert len(entry["requiredItems"]) == 3 and len({m["itemId"] for m in entry["requiredItems"]}) == 3
        inventory_stacks[item_id] = sum(math.ceil(m["amount"]/64) for m in entry["requiredItems"])
        assert inventory_stacks[item_id]+1 <= 32
        for mat in entry["requiredItems"] + trans["requiredMaterials"]:
            assert mat["itemId"] in ids and isinstance(mat["amount"], int) and mat["amount"] > 0
            assert mat["itemId"] in sources and sources[mat["itemId"]]["sourceMinLevel"] <= 30
        if eq["slot"] == "CHEST":
            assert any(sources[m["itemId"]]["kind"] == "gathering" and "PICKAXE" in sources[m["itemId"]]["requiredToolTags"] for m in entry["requiredItems"])
    assert all(len(v) == len(set(v)) for v in slots.values())
    assert set(set_members) == set(SETS)
    for set_id, members in set_members.items():
        assert Counter(members) == Counter({"HEAD": 1, "CHEST": 1, "LEGS": 1, "FEET": 1})
        path = ROOT/ids[set_id][0]
        data = masters[path]
        assert data["schemaVersion"] == 1 and '#   progression: 29\n' in path.read_text(encoding="utf-8")
        assert data["pieces"] == [{"count": 2, "stats": [{"status": SETS[set_id], "type": "FLAT", "value": 10}]}, {"count": 4, "stats": [{"status": "ACCURACY", "type": "FLAT", "value": 2}]}]
        series = [p for p in products.values() if p.get("setId") == set_id]
        chest, = [p for p in series if p["equipmentSlot"] == "CHEST"]
        assert all(sum(m["amount"] for m in chest["requiredItems"]) > sum(m["amount"] for m in p["requiredItems"]) for p in series if p != chest)
    with (HERE/"material_manifest.csv").open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 19
    for row in rows:
        entry = products[row["item_id"]]
        for key, field in (("gold", "priceGold"), ("slot", "slot"), ("page", "page")):
            assert int(row[key]) == entry[field]
        for i, mat in enumerate(entry["requiredItems"], 1):
            assert row[f"material_{i}"] == mat["itemId"] and int(row[f"amount_{i}"]) == mat["amount"]
        assert int(row["evolution_gold"]) == entry["evolution"]["requiredCurrency"]
        for i, mat in enumerate(entry["evolution"]["requiredMaterials"], 1):
            assert row[f"evolution_material_{i}"] == mat["itemId"] and int(row[f"evolution_amount_{i}"]) == mat["amount"]
    allowed = {p.relative_to(ROOT).as_posix() for p, _ in items.values()} | {f"40_filebase/10.features.item/20.equipment/set_effect/v1.{s}.yml" for s in SETS}
    for path in git("diff", "--name-only", BASE) + git("ls-files", "--others", "--exclude-standard"):
        assert path in allowed or path.startswith("00_docs/99_資料/eriva-lv30-astra/"), path
    result = {"status": "PASS", "baseCommit": BASE, "yamlFilesParsed": len(masters), "equipmentCount": len(items),
              "setCount": len(SETS), "manifestProductCount": len(products), "introducedIdDuplicates": [],
              "unchangedBaselineCrossNamespaceDuplicates": duplicates, "requiredInventoryStacks": inventory_stacks,
              "attributeProbability": {"allZero": "0.857375", "exactlyOneNonzero": "0.135375", "exactlyTwoNonzero": "0.007125", "allThreeNonzero": "0.000125", "anyNonzero": "0.142625"},
              "ranges": ranges, "limitations": ["Markdown schema subset checked; actual application importer not run", "Source placement, purchase, persistence, appearance and 100-battle combat not run", "Shared shops/recipes are proposals for later integration"]}
    (HERE/"validation_result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")
    print(json.dumps({k: v for k, v in result.items() if k != "ranges"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
