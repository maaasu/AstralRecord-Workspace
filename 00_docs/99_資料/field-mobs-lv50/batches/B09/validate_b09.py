"""B09の読み取り専用Filebase・参照・編集範囲監査。Pluginテストではない。"""
import argparse
import copy
import json
import math
import re
import subprocess
from collections import defaultdict
from pathlib import Path
import yaml

BASE = "3444b74b2"
BATCH = "00_docs/99_資料/field-mobs-lv50/batches/B09"
class UniqueLoader(yaml.SafeLoader):
    pass
def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise yaml.constructor.ConstructorError("duplicate", node.start_mark, str(key), key_node.start_mark)
        result[key] = loader.construct_object(value_node, deep=deep)
    return result
UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
def merge(parent, child):
    result = copy.deepcopy(parent)
    for key, value in child.items():
        result[key] = merge(result[key], value) if isinstance(value, dict) and isinstance(result.get(key), dict) else copy.deepcopy(value)
    return result
def refs(value):
    if isinstance(value, dict):
        if "ref" in value: yield value["ref"]
        for nested in value.values(): yield from refs(nested)
    elif isinstance(value, list):
        for nested in value: yield from refs(nested)
def require(condition, message):
    if not condition: raise AssertionError(message)
def git(root, *args):
    return subprocess.check_output(["git", "-c", "core.quotepath=false", "-C", str(root), *args], encoding="utf-8").splitlines()
def validate(root):
    manifest = json.loads((root/BATCH/"REGISTRATION_MANIFEST.json").read_text(encoding="utf-8"))
    require(manifest["batch"] == "B09" and manifest["base_commit"] == BASE, "割当/基点")
    allowed = manifest["owned_paths"]
    changes = set(git(root,"diff","--name-only",BASE)) | set(git(root,"ls-files","--others","--exclude-standard"))
    require(all(any(p == a or a.endswith("/") and p.startswith(a) for a in allowed) for p in changes), "所有範囲外: "+str(changes))
    documents, ids = {}, defaultdict(list)
    for path in sorted((root/"40_filebase").rglob("*.yml")):
        doc = yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)
        documents[path] = doc
        if isinstance(doc,dict) and doc.get("id"):
            parts = path.relative_to(root/"40_filebase").parts
            space = ("set" if "set_effect" in parts else "item") if parts[0]=="10.features.item" else ("loot_"+parts[1] if parts[0]=="80.shared.loot" else {"40.features.mob":"mob","30.features.skill":"skill","70.shared.buff":"buff","60.features.world":"world"}.get(parts[0],parts[0]))
            ids[(space,str(doc["id"]))].append(path)
    require(all(len(paths)==1 for paths in ids.values()), "master ID重複")
    statuses = [s["id"] for s in documents[root/"40_filebase/75.shared.status/v1.status_types.yml"]["statuses"]]
    tags = documents[root/"40_filebase/76.shared.tag/v1.tags.yml"]["tags"]
    require(len(statuses)==len(set(statuses)), "status重複")
    tag_ids = [t["id"] for t in tags]
    require(len(tag_ids)==len(set(tag_ids)), "tag重複")
    mob_tags = {t["id"] for t in tags if "MOB" in t["appliesTo"]}
    used_items = {item for space,item in ids if space=="item" and "10a00280"<=item<="10a00289"}
    require(used_items=={"10a00280","10a00281"}, "素材予約枠")
    result = dict(yaml_files=len(documents),master_ids=len(ids),status_ids=len(statuses),tag_ids=len(tag_ids),changed_paths=sorted(changes),profiles=[],references=[],unregistered_skills=[])
    central = (root/"10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/AstralRecord.java").read_text(encoding="utf-8")
    for entry in manifest["executors"]:
        path = root/f"40_filebase/40.features.mob/enemy/v1.{entry['mob_id']}.yml"
        doc = documents[path]
        levels = [p["level"] for p in doc["levels"]]
        require(doc["schemaVersion"]==1 and doc["type"]=="MOB" and doc["category"]=="ENEMY", "mob schema")
        require(levels==entry["levels"] and len(levels)==len(set(levels))==4 and doc["level"]==min(levels) and max(levels)<=50, "levels")
        require(all(b-a==2 for a,b in zip(levels,levels[1:])), "2Lv刻み")
        require(doc["icon"]==doc["entityType"]+"_SPAWN_EGG" and set(doc["tags"])<=mob_tags, "icon/tag")
        inherited={k:v for k,v in doc.items() if k!="levels"}
        prior_hp=prior_exp=0
        for profile in doc["levels"]:
            resolved=merge(inherited,profile)
            stats={s["status"]:s["value"] for s in resolved["baseStats"]}
            require(len(stats)==len(resolved["baseStats"]) and set(stats)<=set(statuses), "stats ID")
            require(all(isinstance(v,(float,int)) and math.isfinite(v) and v>0 for v in stats.values()), "stats値")
            require(stats["MAX_HEALTH"]>prior_hp and resolved["drops"]["exp"]>prior_exp, "成長")
            prior_hp,prior_exp=stats["MAX_HEALTH"],resolved["drops"]["exp"]
            combat=resolved["ai"]["combat"]
            require(combat["skills"]==[dict(id=entry["implementation_id"],activationRange=entry["activation_range"],cooldownTicks=entry["cooldown_ticks"],castTimeTicks=0)], "skill timing")
            require("money" not in resolved["drops"] and "lootTable" not in resolved["drops"], "共通loot/Gold")
            require(resolved["drops"]["items"]==[dict(itemId={"ref":"item:"+m["id"]},rate=m["rate"],amount=1,luckAffected=True,hidden=False) for m in entry["materials"]], "drop継承")
            for ref in refs(resolved):
                require(tuple(ref.split(":",1)) in ids,"参照未解決: "+ref)
                result["references"].append(ref)
            if "ember_slug" in doc["id"]:
                require(stats.get("MAGIC_ATTACK",0)>0 and combat["normalAttack"]==dict(range=2,intervalTicks=40), "炎魔法/近接")
            else: require(combat["style"]=="RANGED" and "normalAttack" not in combat, "落石専門")
            result["profiles"].append(dict(mob_id=doc["id"],level=profile["level"],stats=stats,exp=resolved["drops"]["exp"]))
        class_name=entry["class"].split(".")[-1]
        source=(root/next(p for p in allowed if p.endswith(class_name+".java"))).read_text(encoding="utf-8")
        require(f'SKILL_ID = "{entry["implementation_id"]}"' in source,"executor ID")
        if not re.search(r"mobSkillRegistry\s*\.\s*register\s*\(\s*("+re.escape(entry["field"])+r"|new\s+"+class_name+r"\s*\()",central):
            result["unregistered_skills"].append(entry["implementation_id"])
        for m in entry["materials"]:
            material=documents[ids[("item",m["id"])][0]]
            require(material["category"]=="material" and re.fullmatch(r"10[a-y][0-9]{5}",material["id"]), "item schema")
            require(material["name"]==m["name"] and material["rarity"]==m["rarity"] and material["saleValue"]==m["saleValue"] and material["maxStack"]==64 and not material["unTradeable"] and not material["unSellable"], "素材経済")
    for path in changes:
        if path.endswith(".yml"):
            text=(root/path).read_text(encoding="utf-8")
            require("#   motif:" in text and "#   progression:" in text,"metadata")
    result["references"]=sorted(set(result["references"]))
    result["balance_projection"] = []
    mitigation = lambda d, r: max(0, 1 - (d/r)/2.5) ** 1.35691545
    for profile in result["profiles"]:
        index=(profile["level"]-38)/2
        player_r, player_hp, player_def=5100+450*index,14300+1200*index,5270+460*index
        stats=profile["stats"]
        player_dps=player_r*mitigation(stats["DEFENSE"],player_r)*1.30*0.92*1.60
        if "vulture" in profile["mob_id"]:
            mob_dps=stats["ATTACK"]*mitigation(player_def,stats["ATTACK"])*1.2*0.915/9
        else:
            # 予告で停止する通常攻撃を停止しない上限側。仮想MAGIC_DEFENSEはDEFENSEと同値。
            mob_dps=(stats["ATTACK"]*mitigation(player_def,stats["ATTACK"])/2
                     + stats["MAGIC_ATTACK"]*mitigation(player_def,stats["MAGIC_ATTACK"])*1.4/12)*0.915
        result["balance_projection"].append(dict(mob_id=profile["mob_id"],level=profile["level"],
            virtual_ttk_seconds=round(stats["MAX_HEALTH"]/player_dps,2),
            virtual_survival_all_skill_hits_seconds=round(player_hp/mob_dps,2),
            current_r416_58_damage_multiplier=round(mitigation(stats["DEFENSE"],416.58),4)))
    result["status"]="passed_static_pending_registration_and_live_acceptance"
    return result
if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root",type=Path,default=Path(__file__).resolve().parents[5])
    print(json.dumps(validate(parser.parse_args().repo_root.resolve()),ensure_ascii=False,indent=2))
