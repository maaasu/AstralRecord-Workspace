"""B01 専用の読み取り専用マスタ検証。実サーバーや恒久JUnitを起動しない。"""
from __future__ import annotations
import collections
import json
import math
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[5]
BATCH = ROOT / "00_docs/99_資料/field-mobs-lv50/batches/B01"
manifest = json.loads((BATCH / "REGISTRATION.json").read_text(encoding="utf-8"))
issues: list[str] = []
counts = collections.Counter()
masters: dict[tuple[str, str], list[str]] = collections.defaultdict(list)
parsed: dict[Path, object] = {}

def require(condition: bool, message: str) -> None:
    if not condition:
        issues.append(message)

def namespace(path: Path) -> str:
    parts = path.relative_to(ROOT / "40_filebase").parts
    group = parts[0]
    if group == "10.features.item":
        return "item"
    if group == "40.features.mob":
        return "mob"
    if group == "80.shared.loot":
        return "loot_" + parts[1]
    return group

for path in sorted((ROOT / "40_filebase").rglob("*.yml")):
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
    except (UnicodeError, yaml.YAMLError) as error:
        issues.append(f"YAML parse failed: {path.relative_to(ROOT)}: {error}")
        continue
    parsed[path] = value
    counts["yaml_files"] += 1
    if not isinstance(value, dict):
        continue
    entries = [(namespace(path), value)] if "id" in value else [
        (namespace(path) + ":" + collection, row)
        for collection, rows in value.items() if isinstance(rows, list)
        for row in rows if isinstance(row, dict) and "id" in row
    ]
    # 共有catalogのcategory IDとstatus/tag IDは異なるnamespace。
    for scope, entry in entries:
        identifier = str(entry["id"])
        masters[(scope, identifier)].append(str(path.relative_to(ROOT)))
        counts["master_ids"] += 1

for key, paths in masters.items():
    require(len(paths) == 1, f"duplicate master ID {key}: {paths}")

catalog = parsed[ROOT / "40_filebase/76.shared.tag/v1.tags.yml"]
allowed_tags = {row["id"] for row in catalog["tags"] if "MOB" in row["appliesTo"]}
status_catalog = parsed[ROOT / "40_filebase/75.shared.status/v1.status_types.yml"]
# カタログのコレクション名に依存せず、明示されたステータス定義だけを列挙する。
status_ids = {row["id"] for rows in status_catalog.values() if isinstance(rows, list)
              for row in rows if isinstance(row, dict) and "id" in row}
jp = re.compile(r"[\u3040-\u30ff\u3400-\u9fff]")

new_yaml: list[Path] = []
for relative in manifest["ownedFiles"]:
    if relative.endswith(".yml"):
        new_yaml.append(ROOT / relative)
for path in new_yaml:
    data = parsed[path]
    text = path.read_text(encoding="utf-8")
    require(isinstance(data, dict), f"not a mapping: {path.name}")
    require(data.get("schemaVersion") == 1, f"schemaVersion: {path.name}")
    require("#   motif:" in text and re.search(r"#   progression: [1-9]\d*", text),
            f"design metadata: {path.name}")
    require(jp.search(data.get("name", "")) is not None, f"Japanese name: {path.name}")
    require(all(jp.search(line) for line in data.get("lore", [])), f"Japanese lore: {path.name}")
    require(len(masters[(namespace(path), str(data["id"]))]) == 1, f"reserved ID uniqueness: {path.name}")
    counts["changed_yaml"] += 1

def references(value):
    if isinstance(value, dict):
        for key, row in value.items():
            if key == "ref":
                yield row
            else:
                yield from references(row)
    elif isinstance(value, list):
        for row in value:
            yield from references(row)

for path in new_yaml:
    for ref in references(parsed[path]):
        prefix, identifier = ref.split(":", 1)
        mapped = {"item": "item", "mob": "mob", "loot_table": "loot_table",
                  "loot_pool": "loot_pool", "buff": "70.shared.buff",
                  "skill": "30.features.skill"}.get(prefix, prefix)
        require((mapped, identifier) in masters, f"unresolved ref: {path.name}: {ref}")
        counts["changed_references"] += 1

for registration in manifest["executors"]:
    path = ROOT / f"40_filebase/40.features.mob/enemy/v1.{registration['mobId']}.yml"
    data = parsed[path]
    require(data["type"] == "MOB" and data["category"] == "ENEMY", f"enemy category: {path.name}")
    require(data["level"] == 22, f"default minimum level: {path.name}")
    levels = [row["level"] for row in data["levels"]]
    require(levels == [22, 24, 26, 28], f"assigned level list: {path.name}")
    require(len(set(levels)) == len(levels) and max(levels) <= 50 and 4 <= max(levels) - min(levels) <= 10,
            f"level span/duplicate/max: {path.name}")
    require(data["icon"] == data["entityType"] + "_SPAWN_EGG", f"model icon: {path.name}")
    require(set(data["tags"]) <= allowed_tags, f"MOB tags: {path.name}")
    combat = data["ai"]["combat"]
    require(combat["style"] == "MELEE" and combat["normalAttack"]["range"] > 0
            and combat["normalAttack"]["intervalTicks"] > 0, f"normal melee: {path.name}")
    require(len(combat["skills"]) == 1, f"dedicated skill count: {path.name}")
    binding = combat["skills"][0]
    require(binding["id"] == registration["skillId"] and binding["id"].startswith("mob_"),
            f"manifest skill match: {path.name}")
    require(not binding.get("params") and binding["activationRange"] > 0 and binding["cooldownTicks"] > 0
            and binding["castTimeTicks"] > 0, f"binding settings: {path.name}")
    source = (ROOT / registration["source"]).read_text(encoding="utf-8")
    require(f'SKILL_ID = "{registration["skillId"]}"' in source, f"executor ID: {path.name}")
    require("package " + registration["class"].rsplit(".", 1)[0] + ";" in source,
            f"executor package: {path.name}")
    for row in data["levels"]:
        require(set(row["drops"]) == {"exp"}, f"shared drops inherited: {path.name} Lv{row['level']}")
        for stat in row["baseStats"]:
            require(stat["status"] in status_ids and isinstance(stat["value"], (int, float))
                    and math.isfinite(stat["value"]), f"known finite status: {path.name}: {stat}")
        counts["level_profiles"] += 1
    items = data["drops"]["items"]
    material_rows = [row for row in manifest["materials"] if row["mobId"] == data["id"]]
    require(len(items) == len(material_rows) and "lootTable" not in data["drops"], f"direct dedicated drops: {path.name}")
    for material in material_rows:
        item = next((row for row in items if row["itemId"]["ref"] == "item:" + material["itemId"]), None)
        require(item is not None, f"material drop: {material['itemId']}")
        if item is not None:
            require(item["rate"] == material["rate"] and 0 < item["rate"] <= 100
                    and item["amount"] == material["amount"] == 1 and item["luckAffected"]
                    and not item["hidden"], f"rate/amount/visibility: {material['itemId']}")
        item_path = next(p for p in new_yaml if isinstance(parsed[p], dict) and parsed[p]["id"] == material["itemId"])
        item_data = parsed[item_path]
        require(re.fullmatch(r"10[a-y]\d{5}", item_data["id"]) is not None and item_data["category"] == "material",
                f"material ID/category: {item_path.name}")
        require(item_data["rarity"] == material["rarity"] and item_data["maxStack"] == 64
                and item_data["saleValue"] > 0 and not item_data["unSellable"] and not item_data["unTradeable"],
                f"material rarity/sale/stack: {item_path.name}")
    counts["manifest_executors"] += 1

print(json.dumps({"counts": dict(counts), "errors": issues,
                  "runtime_status": manifest["status"]}, ensure_ascii=False, indent=2))
sys.exit(1 if issues else 0)
