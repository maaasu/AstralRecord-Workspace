"""B04の引き渡し用静的検証。サーバー起動・build・DBアクセスは行わない。"""
from __future__ import annotations

from collections import defaultdict
import json
from pathlib import Path
import re

import yaml


ROOT = Path(__file__).resolve().parents[5]
BATCH = Path(__file__).resolve().parent


class UniqueLoader(yaml.SafeLoader):
    """同じMap内のキー重複を見逃さないLoader。"""


def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)


def read(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)


def namespace(path):
    parts = path.relative_to(ROOT / "40_filebase").parts
    category = parts[0]
    if category == "10.features.item":
        return "item"
    if category == "40.features.mob":
        return "mob"
    if category == "80.shared.loot":
        return "loot_" + parts[1]
    return category


def references(node):
    if isinstance(node, dict):
        if "ref" in node:
            yield node["ref"]
        for value in node.values():
            yield from references(value)
    elif isinstance(node, list):
        for value in node:
            yield from references(value)


def main():
    manifest = json.loads((BATCH / "registration-manifest.json").read_text(encoding="utf-8"))
    ids = defaultdict(dict)
    errors = []
    count = 0
    for path in sorted((ROOT / "40_filebase").rglob("*.yml")):
        data = read(path)
        count += 1
        if not isinstance(data, dict) or "id" not in data:
            continue
        ns = namespace(path)
        id = str(data["id"])
        if id in ids[ns]:
            errors.append(f"duplicate master ID {ns}:{id}: {ids[ns][id]} / {path}")
        ids[ns][id] = path
    assert not errors, "\n".join(errors)
    status_data = read(ROOT / "40_filebase/75.shared.status/v1.status_types.yml")
    # 共有カタログに定義されたstatus IDだけを許可する。
    def catalog_ids(node):
        if isinstance(node, dict):
            if "id" in node:
                yield str(node["id"])
            for value in node.values():
                yield from catalog_ids(value)
        elif isinstance(node, list):
            for value in node:
                yield from catalog_ids(value)
    statuses = set(catalog_ids(status_data))
    tags = {x["id"] for x in read(ROOT / "40_filebase/76.shared.tag/v1.tags.yml")["tags"] if "MOB" in x["appliesTo"]}
    expected = {x["mob_id"]: x for x in manifest["executors"]}
    changed = manifest["master_paths"]
    for relative in changed:
        path = ROOT / relative
        data = read(path)
        text = path.read_text(encoding="utf-8")
        assert "#   motif:" in text and re.search(r"#   progression: [1-9]\d*", text)
        assert data["schemaVersion"] == 1 and data["name"] and any(ord(c) > 127 for c in data["name"])
        for ref in references(data):
            ns, id = ref.split(":", 1)
            assert id in ids[ns], f"unresolved reference: {relative} {ref}"
        if data.get("type") == "MOB":
            assert data["category"] == "ENEMY" and data["icon"] == data["entityType"] + "_SPAWN_EGG"
            assert [x["level"] for x in data["levels"]] == [42, 44, 46, 48]
            assert data["level"] == min(x["level"] for x in data["levels"])
            assert max(x["level"] for x in data["levels"]) <= 50
            assert 4 <= max(x["level"] for x in data["levels"]) - data["level"] <= 10
            assert set(data["tags"]) <= tags
            combat = data["ai"]["combat"]
            assert combat["normalAttack"]["range"] > 0 and combat["normalAttack"]["intervalTicks"] > 0
            assert len(combat["skills"]) == 1 and combat["skills"][0]["id"] == expected[data["id"]]["implementation_id"]
            binding = combat["skills"][0]
            assert binding["castTimeTicks"] == 0 and binding["cooldownTicks"] >= 120
            assert 0 < binding["activationRange"] <= expected[data["id"]]["maximum_range"]
            source = (ROOT / expected[data["id"]]["source_path"]).read_text(encoding="utf-8")
            assert binding["id"] in source and "ParticleDisplayService" in source
            assert "onPluginDisable" in source and "DamageElement.NONE" in source
            for profile in [data] + data["levels"]:
                assert all(s["status"] in statuses and s["value"] >= 0 for s in profile["baseStats"])
                assert profile["drops"]["exp"] > 0
            for drop in data["drops"]["items"]:
                assert 0 < drop["rate"] <= 100 and re.fullmatch(r"[1-9]\d*", str(drop["amount"]))
            assert "lootTable" not in data["drops"]  # 共有tierを暗黙に追加しない。
        else:
            assert data["category"] == "material" and re.fullmatch(r"10a0023[0-3]", data["id"])
            assert data["rarity"] in {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"}
            assert data["saleValue"] >= 0 and 1 <= data["maxStack"] <= 64
    print(f"PASS: parsed {count} filebase YAML; no duplicate top-level master IDs by namespace")
    print(f"PASS: {len(changed)} changed YAML, levels, status/tag IDs, changed references, skill manifest and drops")
    print("PENDING: both executors require central registration; full build/test and in-game validation not run")


if __name__ == "__main__":
    main()
