"""B05専用の軽量検証。Mavenやserverを起動せず、YAMLと実装幾何処理を確認する。"""
from __future__ import annotations

import argparse
from collections import defaultdict
import csv
from copy import deepcopy
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile

import yaml

BATCH = Path(__file__).resolve().parent
ROOT = BATCH.parents[4]
BASE = "e2058ade10a682118382e70aa01c7db758ac81c6"
MOB_DIR = ROOT / "40_filebase/40.features.mob/enemy"
ITEM_DIR = ROOT / "40_filebase/10.features.item/10.material"
JAVA_DIR = ROOT / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field"


class UniqueLoader(yaml.SafeLoader):
    pass


def mapping(loader, node, deep=False):
    loader.flatten_mapping(node)
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate YAML key {key!r} at {key_node.start_mark}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)


def load(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)


def run(*args):
    completed = subprocess.run(args, cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace")
    if completed.returncode:
        raise RuntimeError(f"{args}:\n{completed.stdout}{completed.stderr}")
    return completed.stdout + completed.stderr


def merged(parent, child):
    result = deepcopy(parent)
    for key, value in child.items():
        result[key] = merged(result[key], value) if isinstance(value, dict) and isinstance(result.get(key), dict) else deepcopy(value)
    return result


def refs(value):
    if isinstance(value, dict):
        if "ref" in value:
            yield value["ref"]
        for child in value.values():
            yield from refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from refs(child)


def validate_mob(doc, registration, status_ids, tags, expected_drops):
    assert doc["category"] == "ENEMY" and doc["type"] == "MOB" and doc["schemaVersion"] == 1
    assert doc["icon"] == doc["entityType"] + "_SPAWN_EGG"
    assert doc["level"] == 24 and [p["level"] for p in doc["levels"]] == [24, 26, 28, 30]
    assert max(p["level"] for p in doc["levels"]) <= 50
    assert 4 <= doc["levels"][-1]["level"] - doc["level"] <= 10
    for tag in doc.get("tags", []):
        assert tag in tags and "MOB" in tags[tag]["appliesTo"], tag
    previous = None
    for profile in doc["levels"]:
        resolved = merged(doc, profile)
        stats = {s["status"]: s["value"] for s in resolved["baseStats"]}
        assert len(stats) == len(resolved["baseStats"]) and stats.keys() <= status_ids
        assert all(isinstance(v, (float, int)) and math.isfinite(v) and v >= 0 for v in stats.values())
        if previous:
            assert all(stats[k] > previous[k] for k in ("MAX_HEALTH", "ATTACK", "DEFENSE"))
        previous = stats
        combat = resolved["ai"]["combat"]
        if doc["id"] == "field_moss_fox":
            assert combat["normalAttack"]["range"] > 0 and combat["normalAttack"]["intervalTicks"] > 0
        else:
            assert "normalAttack" not in combat  # 既存射手と同じ固有技中心。壁越し通常遠隔攻撃を作らない。
        assert len(combat["skills"]) == 1 and combat["skills"][0]["id"] == registration["implementationId"]
        assert combat["skills"][0]["castTimeTicks"] > 0 and combat["skills"][0].get("params", {}) == {}
        assert resolved["drops"]["exp"] > 0 and "lootTable" not in resolved["drops"]
        actual = {drop["itemId"]["ref"].split(":")[1]: drop["rate"] for drop in resolved["drops"]["items"]}
        assert actual == expected_drops
        for drop in resolved["drops"]["items"]:
            assert drop["amount"] == 1 and drop["luckAffected"] is True and drop["hidden"] is False
    assert re.search(r"[\u3040-\u30ff\u4e00-\u9fff]", doc["name"])


def validate():
    paths = list((ROOT / "40_filebase").rglob("*.yml"))
    ids = defaultdict(dict)
    for path in paths:
        data = load(path)
        if isinstance(data, dict) and "id" in data:
            namespace = path.relative_to(ROOT / "40_filebase").parts[0]
            assert data["id"] not in ids[namespace], (data["id"], path, ids[namespace].get(data["id"]))
            ids[namespace][data["id"]] = path
    status_ids = {s["id"] for s in load(ROOT / "40_filebase/75.shared.status/v1.status_types.yml")["statuses"]}
    tags = {t["id"]: t for t in load(ROOT / "40_filebase/76.shared.tag/v1.tags.yml")["tags"]}
    manifest = load(BATCH / "registration_manifest.yml")
    reservations = {r["mob_id"]: r for r in csv.DictReader((BATCH.parents[1] / "BATCH_IDS.csv").open(encoding="utf-8-sig")) if r["batch"] == "B05"}
    expectations = {"field_bark_beetle": {"10a00240": 35}, "field_moss_fox": {"10a00241": 35, "10a00242": 8}}
    owned = []
    for registration in manifest["executors"]:
        mob_id = registration["mobId"]
        path = MOB_DIR / f"v1.{mob_id}.yml"
        owned.append(path)
        doc = load(path)
        assert doc["id"] == mob_id and registration["implementationId"] == reservations[mob_id]["skill_id"]
        validate_mob(doc, registration, status_ids, tags, expectations[mob_id])
        for reference in refs(doc):
            namespace, item_id = reference.split(":", 1)
            assert namespace == "item" and item_id in ids["10.features.item"], reference
        source_path = ROOT / reservations[mob_id]["executor_path"]
        owned.append(source_path)
        source = source_path.read_text(encoding="utf-8")
        assert f'public static final String SKILL_ID = "{registration["implementationId"]}"' in source
        assert source_path.stem == registration["class"].split(".")[-1]
        assert "public void clearCasterState(" in source and "public void stop()" in source
        for token in ("isMobCombatTarget(context.target())", "MobState.COMBAT", "context.target().getUniqueId().equals(mob.targetId())",
                      "conditionService.canRunAi", "conditionService.canAttack", "conditionService.canCastSkill", "scriptedAction(false)"):
            assert token in source, (source_path, token)
        # 異常fixtureがレベル・参照・報酬の制約を通り抜けないか専用validator自体を確認します。
        for mutate in (lambda d: d["levels"].append({"level": 52}),
                       lambda d: d["drops"]["items"][0].update(rate=101),
                       lambda d: d["ai"]["combat"]["skills"][0].update(params={"unsupported": 1})):
            bad = deepcopy(doc)
            mutate(bad)
            try:
                validate_mob(bad, registration, status_ids, tags, expectations[mob_id])
            except (AssertionError, KeyError):
                pass
            else:
                raise AssertionError("invalid fixture accepted")
    for material in manifest["materials"]:
        path = ids["10.features.item"][material["id"]]
        owned.append(path)
        doc = load(path)
        assert re.fullmatch(r"10[a-y]\d{5}", doc["id"])
        assert doc["category"] == "material" and doc["schemaVersion"] == 1 and doc["maxStack"] == 64
        assert material["name"] in doc["name"] and doc["saleValue"] == material["saleValue"]
        assert material["ratePercent"] == expectations[material["source"]][material["id"]]
        assert doc["rarity"] == ("UNCOMMON" if material["id"] == "10a00242" else "COMMON")
        assert doc["unTradeable"] is False and doc["unSellable"] is False
    for path in owned:
        if path.suffix == ".yml":
            assert re.match(r'# design:\n#   motif: "[^"\n]+"\n#   progression: 24\n', path.read_text(encoding="utf-8"))
    # 生存状態を持つのは専用taskだけ。残留バフ・実体・射出物・共通lootを作らないことを静的確認。
    bark = (JAVA_DIR / "BarkBeetleMobSkillExecutor.java").read_text(encoding="utf-8")
    fox = (JAVA_DIR / "MossFoxMobSkillExecutor.java").read_text(encoding="utf-8")
    assert "struck.add(victim.getUniqueId())" in bark and "distance < nearest" in bark
    assert "elapsed >= 80" in fox and "(elapsed - 20) % 20 == 0" in fox and "holdingAi = false" in fox
    assert all("runTaskTimer(mobService.plugin(), 5L, 5L)" in source for source in (bark, fox))
    allowed = {str(p.relative_to(ROOT)).replace("\\", "/") for p in owned}
    changes = set(run("git", "-c", "core.quotepath=false", "diff", "--name-only", BASE).splitlines()) | set(run("git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines())
    for changed in changes:
        assert changed in allowed or changed.startswith("00_docs/99_資料/field-mobs-lv50/batches/B05/"), changed
    print(f"PASS: {len(paths)} YAML parsed; {sum(len(v) for v in ids.values())} namespace IDs unique; 2 mobs/8 profiles/3 materials/2 manifests; owned diff only; 6 invalid fixtures rejected")


def extract_method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    cursor = opening + 1
    while depth:
        depth += (source[cursor] == "{") - (source[cursor] == "}")
        cursor += 1
    return source[start:cursor]


def geometry(javac, java, paper_jar, dependency_root):
    bark = (JAVA_DIR / "BarkBeetleMobSkillExecutor.java").read_text(encoding="utf-8")
    fox = (JAVA_DIR / "MossFoxMobSkillExecutor.java").read_text(encoding="utf-8")
    constants = "\n".join(re.findall(r"private static final double (?:SHOT_RANGE|RADIUS) = [^;]+;", bark + fox))
    methods = extract_method(bark, "private static double hitDistance(") + "\n" + extract_method(fox, "private static boolean insideSmoke(")
    source = (BATCH / "B05GeometryCheck.java").read_text(encoding="utf-8").replace("// INSERT_PRODUCTION_GEOMETRY", constants + "\n" + methods)
    families = ["guava-", "failureaccess-", "joml-", "adventure-api-", "adventure-key-", "examination-api-", "examination-string-", "annotations-"]
    deps = [p for p in Path(dependency_root).rglob("*.jar") if any(p.name.startswith(f) for f in families)]
    classpath = os.pathsep.join(str(p) for p in [Path(paper_jar), *deps])
    scratch = (ROOT / "99_work").resolve()
    assert scratch.is_relative_to(ROOT.resolve())
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="b05-geometry-", dir=scratch) as temp:
        assert Path(temp).resolve().is_relative_to(scratch)
        test = Path(temp) / "B05GeometryCheck.java"
        test.write_text(source, encoding="utf-8")
        print(run(javac, "-encoding", "UTF-8", "-cp", classpath, "-d", temp, str(test)), end="")
        print(run(java, "-cp", temp + os.pathsep + classpath, "B05GeometryCheck"), end="")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--paper-jar")
    parser.add_argument("--dependency-root", default=str(Path.home() / ".m2/repository"))
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    validate()
    if args.paper_jar:
        geometry(args.javac, args.java, args.paper_jar, args.dependency_root)
