"""Read-only static acceptance of integrated B01/B02, without Maven or a server."""
import csv, json, re, subprocess, sys
from pathlib import Path
import yaml
root = Path(sys.argv[1]).resolve()
class UniqueLoader(yaml.SafeLoader): pass
def mapping(loader, node, deep=False):
    result = {}
    for k, v in node.value:
        key = loader.construct_object(k, deep=deep)
        if key in result: raise ValueError(f"duplicate YAML key: {key}")
        result[key] = loader.construct_object(v, deep=deep)
    return result
UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
def load(path): return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)
masters, data = {}, {}
for path in (root / "40_filebase").rglob("*.yml"):
    value = load(path); data[path] = value
    if not isinstance(value, dict): continue
    parts = path.relative_to(root / "40_filebase").parts
    ns = parts[0] + ("/" + parts[1] if parts[0] == "80.shared.loot" else "")
    entries = [(ns, value)] if "id" in value else [(ns+":"+c, r) for c, rows in value.items() if isinstance(rows,list) for r in rows if isinstance(r,dict) and "id" in r]
    for scope, row in entries:
        key = scope, str(row["id"])
        assert key not in masters, f"duplicate ID: {key}"
        masters[key] = path
source_path = root / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/AstralRecord.java"
source = source_path.read_text(encoding="utf-8")
fields = {"field_reed_hound":("ReedHound", "reedHound", "grass_charge",[22,24,26,28],{"10a00200":40}), "field_brush_crab":("BrushCrab","brushCrab","side_pincer",[22,24,26,28],{"10a00201":45,"10a00202":6}), "field_sunhare":("Sunhare","sunhare","leap_kick",[28,30,32,34],{"10a00210":35}), "field_wind_lizard":("WindLizard","windLizard","wind_tail",[28,30,32,34],{"10a00211":35})}
for mob_id, (cls, field, move, levels, drops) in fields.items():
    cls += "MobSkillExecutor"; field += "MobSkillExecutor"
    java = root / f"10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field/{cls}.java"
    text = java.read_text(encoding="utf-8")
    skill_id = f"mob_{mob_id}_{move}"
    assert f'SKILL_ID = "{skill_id}"' in text
    assert source.count(f"private {cls} {field};") == 1
    assert source.count(f"{field} = new {cls}(") == 1
    assert source.count(f"mobSkillRegistry.register({field});") == 1
    assert source.count(f"{field}.clearCasterState(mobInstanceId);") == 1
    assert source.count(f"{field}.stop();") == 1
    assert source.index(f"{field}.stop();") < source.index("mobService.destroyAll();") < source.index("getServer().getScheduler().cancelTasks(this);")
    assert source.index(f"{field} = new {cls}(") < source.index("mobService.setDestroyListener(")
    assert "public void clearCasterState(" in text and "public void stop()" in text
    assert "actions.remove(context.mob().instanceId(), this)" in text and "scriptedAction(false)" in text
    mob = data[masters[("40.features.mob",mob_id)]]
    assert [r["level"] for r in mob["levels"]] == levels and 4 <= max(levels)-min(levels) <= 10 and max(levels)<=50
    assert mob["ai"]["combat"]["normalAttack"]["range"]>0
    assert [r["id"] for r in mob["ai"]["combat"]["skills"]] == [skill_id]
    assert all(set(r.get("drops",{})) <= {"exp"} for r in mob["levels"])
    actual={r["itemId"]["ref"].split(":",1)[1]:r for r in mob["drops"]["items"]}
    assert set(actual)==set(drops)
    for item, rate in drops.items():
        assert ("10.features.item",item) in masters
        assert actual[item]["rate"]==rate and actual[item]["amount"]==1
# Existing shared destroy callbacks remain in the same order.
old = subprocess.check_output(["git","show","e2058ade:"+source_path.relative_to(root).as_posix()],cwd=root).decode("utf-8")
def listener(s): return s[s.index("mobService.setDestroyListener("):s.index("        var activeSkillTargetingService",s.index("mobService.setDestroyListener("))]
new_listener=listener(source); cursor=0
for line in listener(old).splitlines():
    line=line.strip()
    if line:
        cursor=new_listener.index(line,cursor)+len(line)
# No pre-existing master or spawn setting has changed.
changes = subprocess.check_output(["git","-c","core.quotepath=false","diff","--name-status","e2058ade","--","40_filebase"],cwd=root).decode("utf-8").splitlines()
assert all(line.startswith("A\t") for line in changes)
assert all("41.features.mob.spawner" not in line and "60.features.world" not in line for line in changes)
print(json.dumps({"yaml_files":len(data),"namespace_ids":len(masters),"registered_executors":4,"level_profiles":16,"materials":5,"existing_master_and_spawn_preserved":True,"result":"PASS","limits":"static source binding only; no semantic compile, runtime registry load, Maven or server"},ensure_ascii=False,indent=2))
