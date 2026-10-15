"""Read-only static acceptance of integrated B01/B02/B03, without Maven or a server."""
import copy, csv, json, re, subprocess, sys
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
fields.update({
    "field_shade_hedgehog": ("ShadeHedgehog", "shadeHedgehog", "needle_ring", [36,38,40,42], {"10a00410":35}),
    "field_canopy_owl": ('CanopyOwl', 'canopyOwl', 'leaf_vortex', [36, 38, 40, 42], {'10a00400': 35, '10a00401': 10}),
    "field_thorn_deer": ('ThornDeer', 'thornDeer', 'thorn_charge', [36, 38, 40, 42], {'10a00402': 35}),
    "field_mesa_vulture": ('MesaVulture', 'mesaVulture', 'stone_drop', [38, 40, 42, 44], {'10a00280': 35}),
    "field_ember_slug": ('EmberSlug', 'emberSlug', 'ember_trail', [44, 46, 48, 50], {'10a00281': 35}),
    "field_lava_crab": ('LavaCrab', 'lavaCrab', 'heated_shell', [44, 46, 48, 50], {'10a00290': 35}),
    "field_ash_drake": ('AshDrake', 'ashDrake', 'ash_breath', [44, 46, 48, 50], {'10a00291': 35, '10a00292': 8, '10a00293': 0.15}),
    "field_red_scorpion": ("RedScorpion", "redScorpion", "tail_sting", [38,40,42,44], {"10a00270":35}),
    "field_sand_gecko": ("SandGecko", "sandGecko", "sand_scatter", [38,40,42,44], {"10a00271":35,"10a00272":8}),
    "field_ice_moth": ("IceMoth", "iceMoth", "ice_powder", [32,34,36,38], {"10a00260":35,"10a00261":8}),
    "field_frost_boar": ("FrostBoar", "frostBoar", "frost_charge", [32,34,36,38], {"10a00262":35}),
    "field_root_turtle": ("RootTurtle", "rootTurtle", "root_snare", [24,26,28,30], {"10a00250":35}),
    "field_snow_wolf": ("SnowWolf", "snowWolf", "ice_dash", [32,34,36,38], {"10a00251":35}),
    "field_bark_beetle": ("BarkBeetle", "barkBeetle", "wood_shot", [24,26,28,30], {"10a00240":35}),
    "field_moss_fox": ("MossFox", "mossFox", "moss_smoke", [24,26,28,30], {"10a00241":35,"10a00242":8}),
    "field_stoneback_bison": ("StonebackBison", "stonebackBison", "earth_charge", [42,44,46,48], {"10a00230":35,"10a00231":10,"10a00232":0.25}),
    "field_storm_raptor": ("StormRaptor", "stormRaptor", "diving_strike", [42,44,46,48], {"10a00233":35}),
    "field_plain_ram": ("PlainRam", "plainRam", "horn_thrust", [28,30,32,34], {"10a00220":35, "10a00221":7}),
    "field_blade_mantis": ("BladeMantis", "bladeMantis", "blade_sweep", [42,44,46,48], {"10a00222":35})
})
for mob_id, (cls, field, move, levels, drops) in fields.items():
    cls += "MobSkillExecutor"; field += "MobSkillExecutor"
    java = root / f"10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field/{cls}.java"
    text = java.read_text(encoding="utf-8")
    skill_id = f"mob_{mob_id}_{move}"
    assert f'SKILL_ID = "{skill_id}"' in text
    assert source.count(f"private {cls} {field};") == 1
    assert source.count(f"{field} = new {cls}(") == 1
    assert source.count(f"mobSkillRegistry.register({field});") == 1
    hook = "handleMobDestroyed" if mob_id in ["field_root_turtle","field_snow_wolf","field_ice_moth","field_frost_boar","field_red_scorpion","field_sand_gecko","field_mesa_vulture","field_ember_slug","field_lava_crab","field_ash_drake","field_canopy_owl","field_thorn_deer","field_shade_hedgehog"] else "clearCasterState"
    assert source.count(f"{field}.{hook}(mobInstanceId);") == 1
    assert source.count(f"{field}.stop();") == 1
    assert source.index(f"{field}.stop();") < source.index("mobService.destroyAll();") < source.index("getServer().getScheduler().cancelTasks(this);")
    assert source.index(f"{field} = new {cls}(") < source.index("mobService.setDestroyListener(")
    cleanup = (java.parent/"MesaDebuffSkillSupport.java").read_text(encoding="utf-8") if mob_id in ["field_red_scorpion","field_sand_gecko"] else text
    if mob_id in ["field_lava_crab","field_ash_drake"]: cleanup=(java.parent/"VolcanicSkillSupport.java").read_text(encoding="utf-8")
    assert f"public void {hook}(" in cleanup and "public void stop()" in cleanup
    assert re.search(r"\w+\.remove\(", cleanup) and "scriptedAction(false)" in cleanup
    mob = data[masters[("40.features.mob",mob_id)]]
    assert [r["level"] for r in mob["levels"]] == levels and 4 <= max(levels)-min(levels) <= 10 and max(levels)<=50
    if mob_id in ["field_bark_beetle","field_mesa_vulture"]:
        assert "normalAttack" not in mob["ai"]["combat"]
    else:
        assert mob["ai"]["combat"]["normalAttack"]["range"]>0
    assert [r["id"] for r in mob["ai"]["combat"]["skills"]] == [skill_id]
    assert all(set(r.get("drops",{})) <= {"exp"} for r in mob["levels"])
    actual={r["itemId"]["ref"].split(":",1)[1]:r for r in mob["drops"]["items"]}
    assert set(actual)==set(drops)
    for item, rate in drops.items():
        assert ("10.features.item",item) in masters
        assert actual[item]["rate"]==rate and str(actual[item]["amount"])=="1"
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
master_changes = [line for line in changes if any(path.endswith((".yml", ".yaml")) for path in line.split("\t")[1:])]
existing_materials = {"eriva_bark_sentinel":"10a00300", "eriva_camouflage_zombie":"10a00301", "eriva_grassboar":"10a00302", "eriva_sporecap_beast":"10a00303", "eriva_starhorn_guardian":"10a00304", "eriva_thornfang_wolf":"10a00305", "eriva_underbrush_spider":"10a00306", "maril_sil_dead_hero":"10a00307", "midgard_clay_guard":"10a00308", "midgard_forest_spider":"10a00309", "midgard_forest_wolf":"10a00310", "midgard_grassboar":"10a00311", "midgard_moss_shell":"10a00312", "midgard_savanna_camel":"10a00313", "midgard_savanna_hyena":"10a00314", "midgard_savanna_witch":"10a00315", "midgard_skeleton_archer":"10a00316", "purple_tree_golm":"10a00317"}
allowed_modified = {f"40_filebase/40.features.mob/enemy/v1.{mob}.yml" for mob in existing_materials}
for line in master_changes:
    cells=line.split("\t")
    assert cells[0]=="A" or (cells[0]=="M" and cells[1] in allowed_modified), f"unexpected existing master change: {line}"
assert all("41.features.mob.spawner" not in line and "60.features.world" not in line for line in master_changes)
def merge(base, override):
    value=copy.deepcopy(base)
    for key,row in override.items():
        value[key]=merge(value[key],row) if isinstance(value.get(key),dict) and isinstance(row,dict) else copy.deepcopy(row)
    return value
def remove_material(current, old, item):
    value=copy.deepcopy(current)
    items=value.get("drops",{}).get("items",[])
    added=[r for r in items if r.get("itemId",{}).get("ref")=="item:"+item]
    assert len(added)==1 and added[0]["rate"]==35 and str(added[0]["amount"])=="1" and added[0]["luckAffected"] and not added[0]["hidden"]
    value["drops"]["items"]=[r for r in items if r not in added]
    if "items" not in old.get("drops",{}):
        assert not value["drops"]["items"];del value["drops"]["items"]
    if "levels" in value:
        for profile, original_profile in zip(value["levels"], old["levels"], strict=True):
            if "items" in profile.get("drops", {}):
                remove_material(profile, original_profile, item)
                profile["drops"]["items"] = [row for row in profile["drops"]["items"] if row.get("itemId", {}).get("ref") != "item:"+item]
    assert value==old, f"existing effective data changed: {item}"
assert ("70.shared.buff","field_root_turtle_root_snare") in masters
existing_profiles=0
for mob,item in existing_materials.items():
    path=f"40_filebase/40.features.mob/enemy/v1.{mob}.yml"
    original=yaml.load(subprocess.check_output(["git","show","e2058ade:"+path],cwd=root).decode("utf-8"), Loader=UniqueLoader)
    current=data[root/path]
    remove_material(current,original,item)
    assert ("10.features.item",item) in masters
    original_root={k:v for k,v in original.items() if k!="levels"}
    current_root={k:v for k,v in current.items() if k!="levels"}
    original_profiles={p["level"]:p for p in original.get("levels",[{"level":original["level"]}])}
    for profile in current.get("levels",[{"level":current["level"]}]):
        remove_material(merge(current_root,profile),merge(original_root,original_profiles[profile["level"]]),item)
        existing_profiles+=1
print(json.dumps({"yaml_files":len(data),"namespace_ids":len(masters),"registered_executors":len(fields),"level_profiles":sum(len(v[3]) for v in fields.values()),"materials":sum(len(v[4]) for v in fields.values()),"existing_drop_and_spawn_preserved":True,"existing_material_additions":len(existing_materials),"existing_profiles_checked":existing_profiles,"result":"PASS","limits":"static source binding only; no semantic compile, runtime registry load, Maven or server"},ensure_ascii=False,indent=2))

# All horizontal field-skill block rays use the loaded-chunk guard. Vertical ground rays stay in a checked column.
field_dir=source_path.parent/"feature/mob/skill/field"
raw=[]
for path in field_dir.glob("*.java"):
    if path.name=="FieldMobRayTrace.java": continue
    for line in path.read_text(encoding="utf-8").splitlines():
        if ".rayTraceBlocks(" in line:
            assert "RayTraceResult ground =" in line or "RayTraceResult hit = feet." in line or "return location.getWorld().rayTraceBlocks" in line, (path,line)
            raw.append(path.name)
assert len(raw)==7
assert source.index("if (shadeHedgehogStopFailure != null) throw shadeHedgehogStopFailure;") > source.index("SqlServerManager.getInstance().shutdown();")
