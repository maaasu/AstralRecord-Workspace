"""B02専用の静的検証。Maven/サーバーは起動せず、幾何部分だけ一時javacで確認する。"""
from __future__ import annotations

import argparse
import csv
import os
from pathlib import Path
import re
import subprocess
import tempfile

import yaml

BATCH = Path(__file__).resolve().parent
ROOT = BATCH.parents[4]
BASE = "e2058ade10a682118382e70aa01c7db758ac81c6"
JAVA_DIR = ROOT / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field"
MOBS = {
    "field_sunhare": ("SunhareMobSkillExecutor", "mob_field_sunhare_leap_kick", "10a00210", "RABBIT", 140, 4.5),
    "field_wind_lizard": ("WindLizardMobSkillExecutor", "mob_field_wind_lizard_wind_tail", "10a00211", "SILVERFISH", 150, 3.4),
}


class UniqueLoader(yaml.SafeLoader):
    """重複YAMLキーを黙って上書きしない。"""


def unique_mapping(loader, node, deep=False):
    output = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in output:
            raise ValueError(f"duplicate YAML key: {key}")
        output[key] = loader.construct_object(value_node, deep=deep)
    return output


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def load(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)


def walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk(value)


def run(*args):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if result.stderr:
        print(result.stderr, end="")
    result.check_returncode()
    return result.stdout


def validate():
    masters = {}
    paths = list((ROOT / "40_filebase").rglob("*.yml"))
    for path in paths:
        data = load(path)
        if not isinstance(data, dict) or "id" not in data:
            continue
        parts = path.relative_to(ROOT / "40_filebase").parts
        namespace = parts[0] + ("/" + parts[1] if parts[0] == "80.shared.loot" else "")
        key = (namespace, str(data["id"]))
        assert key not in masters, f"duplicate master ID {key}: {path} / {masters.get(key)}"
        masters[key] = path
    statuses = {str(n["id"]) for n in walk(load(ROOT / "40_filebase/75.shared.status/v1.status_types.yml")) if "id" in n}
    tags = {str(n["id"]) for n in walk(load(ROOT / "40_filebase/76.shared.tag/v1.tags.yml"))
            if "id" in n and "MOB" in n.get("appliesTo", [])}
    reservations = list(csv.DictReader((BATCH.parents[1] / "BATCH_IDS.csv").open(encoding="utf-8-sig")))
    manifest = load(BATCH / "registration_manifest.yml")
    registered = {entry["implementationId"]: entry for entry in manifest["executors"]}
    owned = set()
    for mob_id, (class_name, skill_id, item_id, entity_type, cooldown, activation) in MOBS.items():
        mob_path = ROOT / f"40_filebase/40.features.mob/enemy/v1.{mob_id}.yml"
        owned.add(mob_path.relative_to(ROOT).as_posix())
        mob = load(mob_path)
        assert (mob["id"], mob["type"], mob["category"], mob["entityType"], mob["icon"]) == (
            mob_id, "MOB", "ENEMY", entity_type, entity_type + "_SPAWN_EGG")
        assert [level["level"] for level in mob["levels"]] == [28, 30, 32, 34]
        assert 4 <= mob["levels"][-1]["level"] - mob["levels"][0]["level"] <= 10
        assert max(p["level"] for p in mob["levels"]) <= 50
        assert mob["level"] == 28 and all(tag in tags for tag in mob["tags"])
        expected = {"id": skill_id, "activationRange": activation, "cooldownTicks": cooldown, "castTimeTicks": 12}
        assert mob["ai"]["combat"]["skills"] == [expected]
        assert mob["ai"]["combat"]["normalAttack"]["range"] > 0
        assert mob["ai"]["combat"]["normalAttack"]["intervalTicks"] >= 28
        assert any(r["batch"] == "B02" and r["mob_id"] == mob_id and r["skill_id"] == skill_id for r in reservations)
        assert skill_id in registered and registered[skill_id]["class"].endswith("." + class_name)
        last = None
        for profile in mob["levels"]:
            stats = {v["status"]: v["value"] for v in profile.get("baseStats", mob["baseStats"])}
            assert all(s in statuses for s in stats)
            assert all(stats[s] > 0 for s in ["MAX_HEALTH", "ATTACK", "DEFENSE", "MOVEMENT_SPEED"])
            assert "Lv." + str(profile["level"]) in profile["name"]
            if last:
                assert all(stats[s] > last[s] for s in ["MAX_HEALTH", "ATTACK", "DEFENSE"])
            last = stats
        assert "lootTable" not in mob["drops"]
        assert mob["drops"]["items"] == [{"itemId": {"ref": "item:" + item_id}, "rate": 35.0, "amount": 1,
                                            "luckAffected": True, "hidden": False}]
        assert ("10.features.item", item_id) in masters
        item_path = masters[("10.features.item", item_id)]
        owned.add(item_path.relative_to(ROOT).as_posix())
        item = load(item_path)
        assert re.fullmatch(r"10a\d{5}", item["id"])
        assert item["category"] == "material" and item["rarity"] == "COMMON" and item["maxStack"] == 64
        assert item["saleValue"] > 0 and not item["unTradeable"] and not item["unSellable"]
        for path in [mob_path, item_path]:
            text = path.read_text(encoding="utf-8")
            assert text.startswith("# design:\n#   motif:") and "#   progression: 20" in text
            assert "\ufffd" not in text
        source_path = JAVA_DIR / (class_name + ".java")
        owned.add(source_path.relative_to(ROOT).as_posix())
        source = source_path.read_text(encoding="utf-8")
        assert f'SKILL_ID = "{skill_id}"' in source
        assert "isMobCombatTarget" in source and "AttackType.MELEE" in source and "DamageElement.NONE" in source
        assert "clearCasterState" in source and "public void stop()" in source
        assert "scriptedAction(false)" in source and "actions.remove(context.mob().instanceId(), this)" in source
    # 既存マスターと中央ファイルを変更しない。未追跡ファイルも所有範囲を確認する。
    changed = set(run("git", "-c", "core.quotepath=false", "diff", "--name-only", BASE, "--").splitlines())
    changed.update(run("git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard").splitlines())
    batch_prefix = BATCH.relative_to(ROOT).as_posix() + "/"
    unexpected = {p for p in changed if p not in owned and not p.startswith(batch_prefix)}
    assert not unexpected, f"out-of-scope diff: {unexpected}"
    print(f"PASS: {len(paths)} YAML parsed; {len(masters)} namespace IDs unique; 2 mobs/8 profiles/2 item refs/2 manifests; owned diff only")


def extract_method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def geometry(javac, java, paper_jar, dependency_root):
    source = (JAVA_DIR / "WindLizardMobSkillExecutor.java").read_text(encoding="utf-8")
    constants = "\n".join(re.findall(r"private static final double (?:RADIUS|HALF_ANGLE) = [^;]+;", source))
    methods = "\n".join(extract_method(source, sig) for sig in ["static boolean intersectsFan(", "private static boolean inFan("])
    template = (BATCH / "WindTailGeometryCheck.java").read_text(encoding="utf-8")
    actual = template.replace("// INSERT_PRODUCTION_GEOMETRY", constants + "\n" + methods)
    # Paper APIの型を直接使用。プロジェクト全体のコンパイルは行わない。
    families = ["guava-", "failureaccess-", "joml-", "adventure-api-", "adventure-key-", "examination-api-", "examination-string-", "annotations-"]
    deps = [p for p in Path(dependency_root).rglob("*.jar") if any(p.name.startswith(f) for f in families)]
    classpath = os.pathsep.join(str(p) for p in [Path(paper_jar), *deps])
    scratch_parent = (ROOT / "99_work").resolve()
    assert scratch_parent.is_relative_to(ROOT.resolve())
    scratch_parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="field-b02-", dir=scratch_parent) as temp:
        assert Path(temp).resolve().is_relative_to(scratch_parent)
        test = Path(temp) / "WindTailGeometryCheck.java"
        test.write_text(actual, encoding="utf-8")
        print(run(javac, "-encoding", "UTF-8", "-cp", classpath, "-d", temp, str(test)), end="")
        print(run(java, "-cp", temp + os.pathsep + classpath, "WindTailGeometryCheck"), end="")


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
